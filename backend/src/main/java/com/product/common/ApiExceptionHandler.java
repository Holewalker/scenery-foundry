package com.product.common;

import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.product.asset.AssetTooLargeException;
import com.product.asset.IdempotencyConflictException;
import com.product.asset.UnsupportedAssetMediaTypeException;
import com.product.scene.SceneVersionConflictException;

/**
 * Maps exceptions that would otherwise surface as an opaque 500 onto the API's stable
 * {@code {code, message}} error contract (D5). Domain exceptions that already carry
 * {@code @ResponseStatus} (e.g. {@code InvalidSceneException}) need no entry here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> handleFileTooLarge(MaxUploadSizeExceededException exception) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Uploaded file exceeds the maximum allowed size");
    }

    @ExceptionHandler(AssetTooLargeException.class)
    ResponseEntity<Map<String, String>> handleAssetTooLarge(AssetTooLargeException exception) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(UnsupportedAssetMediaTypeException.class)
    ResponseEntity<Map<String, String>> handleUnsupportedAssetMediaType(UnsupportedAssetMediaTypeException exception) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Map<String, String>> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return body(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(SceneVersionConflictException.class)
    ResponseEntity<Map<String, String>> handleSceneVersionConflict(SceneVersionConflictException exception) {
        return body(HttpStatus.CONFLICT, "SCENE_VERSION_CONFLICT", exception.getMessage());
    }

    private static final Set<String> MEMBERSHIP_FOREIGN_KEYS = Set.of(
        "scene_objects_level_project_fkey", "scene_objects_print_group_project_fkey");

    /** Composite (x_id, project_id) FK violation (V6): same-owner, wrong-project reference — 422, distinct
     * from the 404-on-foreign-ownership pattern (ADR-0003), which is reserved for a DIFFERENT owner.
     * Scoped to exactly the two scene-membership FK constraints (Codex finding on PR3, #44): any other
     * {@code DataIntegrityViolationException} — a unique idempotency-key race, a CHECK violation, etc. —
     * is rethrown and falls through to the default 500, matching this class's stated scope for exceptions
     * it does not explicitly map. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        if (!isMembershipForeignKeyViolation(exception)) {
            throw exception;
        }
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REFERENCE",
            "A referenced resource does not belong to the same project");
    }

    private static boolean isMembershipForeignKeyViolation(DataIntegrityViolationException exception) {
        var message = exception.getMostSpecificCause().getMessage();
        if (message == null) {
            return false;
        }
        return MEMBERSHIP_FOREIGN_KEYS.stream().anyMatch(message::contains);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}

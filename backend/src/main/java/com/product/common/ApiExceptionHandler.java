package com.product.common;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.product.asset.AssetTooLargeException;
import com.product.asset.IdempotencyConflictException;
import com.product.asset.UnsupportedAssetMediaTypeException;

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

    /** Composite (x_id, project_id) FK violation (V6): same-owner, wrong-project reference — 422, distinct
     * from the 404-on-foreign-ownership pattern (ADR-0003), which is reserved for a DIFFERENT owner. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REFERENCE",
            "A referenced resource does not belong to the same project");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}

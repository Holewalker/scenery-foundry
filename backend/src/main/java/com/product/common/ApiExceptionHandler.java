package com.product.common;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps exceptions that would otherwise surface as an opaque 500 onto the API's stable
 * {@code {code, message}} error contract (D5). Domain exceptions that already carry
 * {@code @ResponseStatus} (e.g. {@code InvalidSceneException}) need no entry here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> handleFileTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(Map.of("code", "FILE_TOO_LARGE", "message", "Uploaded file exceeds the maximum allowed size"));
    }
}

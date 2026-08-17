package com.product.common;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Test-only endpoint used to exercise {@link ApiExceptionHandler} without a real multipart upload. */
@RestController
class ThrowingTestController {
    @PostMapping("/api/test/throw-max-upload-size")
    void triggerMaxUploadSizeExceeded() {
        throw new MaxUploadSizeExceededException(200L * 1024 * 1024);
    }
}

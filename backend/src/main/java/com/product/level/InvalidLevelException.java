package com.product.level;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class InvalidLevelException extends IllegalArgumentException {
    public InvalidLevelException(String message) { super(message); }
}

package com.product.scene;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class InvalidSceneException extends IllegalArgumentException {
    public InvalidSceneException(String message) { super(message); }
}

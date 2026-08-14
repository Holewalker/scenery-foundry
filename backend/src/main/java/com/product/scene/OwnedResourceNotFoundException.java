package com.product.scene;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public final class OwnedResourceNotFoundException extends RuntimeException {
    public OwnedResourceNotFoundException() { super("Owned resource was not found"); }
}

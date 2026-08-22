package com.product.printgroup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class InvalidPrintGroupException extends IllegalArgumentException {
    public InvalidPrintGroupException(String message) { super(message); }
}

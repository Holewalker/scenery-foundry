package com.product.piecesexport;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A well-formed request whose print-group composition fails a pieces-export business rule: empty group,
 * a non-READY member (naming the offending scene object/asset), or the 250-object ceiling. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public final class PiecesExportValidationException extends RuntimeException {
    public PiecesExportValidationException(String message) { super(message); }
}

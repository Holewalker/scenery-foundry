package com.product.piecesexport;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when distinct-asset uncompressed bytes exceed {@code app.piecesexport.max-uncompressed-bytes}, checked
 * before a single ZIP byte is written (D5's guardrail requirement). */
@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
public final class PiecesExportTooLargeException extends RuntimeException {
    public PiecesExportTooLargeException(String message) { super(message); }
}

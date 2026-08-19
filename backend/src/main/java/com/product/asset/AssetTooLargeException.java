package com.product.asset;

/** Raised when an uploaded asset exceeds the 200 MiB intake limit, before any parsing is attempted. */
public final class AssetTooLargeException extends RuntimeException {
    public AssetTooLargeException() { super("Uploaded file exceeds the maximum allowed size"); }
}

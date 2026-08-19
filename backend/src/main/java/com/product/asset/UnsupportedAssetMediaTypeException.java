package com.product.asset;

/** Raised when the uploaded content is missing or is not a `.stl` asset. */
public final class UnsupportedAssetMediaTypeException extends RuntimeException {
    public UnsupportedAssetMediaTypeException() { super("Uploaded file must be a valid .stl asset"); }
}

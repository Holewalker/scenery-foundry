package com.product.export;

import java.util.Arrays;

public record ExportSnapshot(byte[] canonicalBytes, String sha256, String canonicalizerContract) {
    public ExportSnapshot { canonicalBytes = Arrays.copyOf(canonicalBytes, canonicalBytes.length); }
    @Override public byte[] canonicalBytes() { return Arrays.copyOf(canonicalBytes, canonicalBytes.length); }
}

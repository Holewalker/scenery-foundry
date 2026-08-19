package com.product.storage;

/** Raised when a storage key cannot be safely resolved: traversal segments, symlink escape, or an unreadable target. */
public final class StorageAccessException extends RuntimeException {
    public StorageAccessException(String storageKey) {
        super("Storage key rejected: " + storageKey);
    }
}

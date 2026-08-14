package com.product.scene;

import java.util.UUID;

final class OwnerScope {
    private OwnerScope() { }
    static void requireOwner(UUID ownerId) {
        if (ownerId == null) throw new OwnedResourceNotFoundException();
    }
}

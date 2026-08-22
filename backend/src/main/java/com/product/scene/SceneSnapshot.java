package com.product.scene;

import java.util.List;

/** A scene's {@code scene_version} paired with its {@code scene_objects}, read as one atomic snapshot
 * (Codex PR #52 finding 1): a client that GETs this pair, then PUTs it straight back with that same
 * version, must never see a spurious {@link SceneVersionConflictException} caused by the GET itself
 * having paired a stale version with objects a concurrent writer had already committed (or vice versa). */
public record SceneSnapshot(long version, List<SceneObject> objects) {
    public SceneSnapshot {
        objects = List.copyOf(objects);
    }
}

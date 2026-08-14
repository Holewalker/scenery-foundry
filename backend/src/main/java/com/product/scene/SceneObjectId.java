package com.product.scene;

public record SceneObjectId(long value) {
    public static final long MIN_VALUE = 1;
    public static final long MAX_VALUE = 9_007_199_254_740_991L;
    public SceneObjectId {
        if (value < MIN_VALUE || value > MAX_VALUE) throw new InvalidSceneException("scene object id is outside the binary64-safe range");
    }
    public static SceneObjectId of(long value) { return new SceneObjectId(value); }
}

package com.product.scene;

import java.util.Arrays;

public record SceneTransform(double[] matrixWorldColumnMajor, double[] quaternionXyzw, double[] scale) {
    private static final double EPSILON = 1e-10;
    public SceneTransform {
        matrixWorldColumnMajor = copy(matrixWorldColumnMajor, 16, "matrix");
        quaternionXyzw = copy(quaternionXyzw, 4, "quaternion");
        scale = copy(scale, 3, "scale");
        for (double value : matrixWorldColumnMajor) finite(value);
        for (double value : quaternionXyzw) finite(value);
        for (double value : scale) finite(value);
        if (Math.abs(matrixWorldColumnMajor[3]) > EPSILON || Math.abs(matrixWorldColumnMajor[7]) > EPSILON
            || Math.abs(matrixWorldColumnMajor[11]) > EPSILON || Math.abs(matrixWorldColumnMajor[15] - 1) > EPSILON) {
            throw new InvalidSceneException("matrix must be affine");
        }
        double norm = 0;
        for (double q : quaternionXyzw) norm += q * q;
        if (Math.abs(norm - 1) > EPSILON) throw new InvalidSceneException("quaternion must be unit length");
        for (double axis : scale) if (axis <= 0) throw new InvalidSceneException("scale must be positive");
    }
    public static SceneTransform of(double[] matrix, double[] quaternion, double[] scale) {
        return new SceneTransform(matrix, quaternion, scale);
    }
    private static double[] copy(double[] values, int expected, String label) {
        if (values == null || values.length != expected) throw new InvalidSceneException(label + " shape is invalid");
        return Arrays.copyOf(values, values.length);
    }
    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new InvalidSceneException("transform must be finite");
    }
}

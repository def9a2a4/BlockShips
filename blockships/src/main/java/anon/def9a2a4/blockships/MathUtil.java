package anon.def9a2a4.blockships;

import org.joml.Matrix4f;

/**
 * Utility class for mathematical operations.
 */
public class MathUtil {

    /**
     * Checks if two JOML Matrix4f objects are approximately equal within an epsilon tolerance.
     * Useful for comparing transformation matrices where floating point precision may vary slightly.
     *
     * @param a First matrix to compare
     * @param b Second matrix to compare
     * @return true if all matrix elements are within epsilon (0.0001) of each other
     */
    public static boolean matricesEqual(Matrix4f a, Matrix4f b) {
        float epsilon = 0.0001f;
        return Math.abs(a.m00() - b.m00()) < epsilon &&
               Math.abs(a.m01() - b.m01()) < epsilon &&
               Math.abs(a.m02() - b.m02()) < epsilon &&
               Math.abs(a.m03() - b.m03()) < epsilon &&
               Math.abs(a.m10() - b.m10()) < epsilon &&
               Math.abs(a.m11() - b.m11()) < epsilon &&
               Math.abs(a.m12() - b.m12()) < epsilon &&
               Math.abs(a.m13() - b.m13()) < epsilon &&
               Math.abs(a.m20() - b.m20()) < epsilon &&
               Math.abs(a.m21() - b.m21()) < epsilon &&
               Math.abs(a.m22() - b.m22()) < epsilon &&
               Math.abs(a.m23() - b.m23()) < epsilon &&
               Math.abs(a.m30() - b.m30()) < epsilon &&
               Math.abs(a.m31() - b.m31()) < epsilon &&
               Math.abs(a.m32() - b.m32()) < epsilon &&
               Math.abs(a.m33() - b.m33()) < epsilon;
    }

    /**
     * Checks if two JOML Matrix4f objects are approximately equal with a custom epsilon.
     *
     * @param a First matrix to compare
     * @param b Second matrix to compare
     * @param epsilon The tolerance for comparison
     * @return true if all matrix elements are within epsilon of each other
     */
    public static boolean matricesEqual(Matrix4f a, Matrix4f b, float epsilon) {
        return Math.abs(a.m00() - b.m00()) < epsilon &&
               Math.abs(a.m01() - b.m01()) < epsilon &&
               Math.abs(a.m02() - b.m02()) < epsilon &&
               Math.abs(a.m03() - b.m03()) < epsilon &&
               Math.abs(a.m10() - b.m10()) < epsilon &&
               Math.abs(a.m11() - b.m11()) < epsilon &&
               Math.abs(a.m12() - b.m12()) < epsilon &&
               Math.abs(a.m13() - b.m13()) < epsilon &&
               Math.abs(a.m20() - b.m20()) < epsilon &&
               Math.abs(a.m21() - b.m21()) < epsilon &&
               Math.abs(a.m22() - b.m22()) < epsilon &&
               Math.abs(a.m23() - b.m23()) < epsilon &&
               Math.abs(a.m30() - b.m30()) < epsilon &&
               Math.abs(a.m31() - b.m31()) < epsilon &&
               Math.abs(a.m32() - b.m32()) < epsilon &&
               Math.abs(a.m33() - b.m33()) < epsilon;
    }
}

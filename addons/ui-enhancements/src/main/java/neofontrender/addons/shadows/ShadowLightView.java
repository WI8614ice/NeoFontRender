package neofontrender.addons.shadows;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Frozen light projection in the same terrain-relative coordinates as the captured body and scene depth. */
public final class ShadowLightView {
    private final Matrix4f projectionView;
    private final boolean directional;
    private final Vector3f light;

    private ShadowLightView(Matrix4f projectionView, boolean directional, Vector3f light) {
        for (float value : projectionView.get(new float[16])) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Finite light projection required");
        if (projectionView.determinant() == 0) throw new IllegalArgumentException("Invertible light projection required");
        this.projectionView = projectionView;
        this.directional = directional; this.light = new Vector3f(light);
    }

    /** Caller supplies the proved source-to-receiver extent, not a guessed camera near/far. */
    public static ShadowLightView directional(float cx, float cy, float cz, float radius,
                                               float toLightX, float toLightY, float toLightZ, float distance) {
        if (!Float.isFinite(radius) || !Float.isFinite(distance) || radius <= 0 || distance <= radius)
            throw new IllegalArgumentException("Directional light extent must include receivers before and after the center");
        Vector3f direction = direction(toLightX, toLightY, toLightZ);
        Vector3f center = point(cx, cy, cz);
        Vector3f eye = new Vector3f(direction).mul(distance).add(center);
        var view = new Matrix4f().lookAt(eye, center, up(direction));
        return new ShadowLightView(new Matrix4f().ortho(-radius, radius, -radius, radius,
                .01F, distance + radius).mul(view), true, direction);
    }

    /** A point-light cone for a bounded receiver region; sources inside that region require another representation. */
    public static ShadowLightView point(float lx, float ly, float lz, float cx, float cy, float cz,
                                         float radius, float far) {
        Vector3f eye = point(lx, ly, lz), center = point(cx, cy, cz);
        float distance = eye.distance(center);
        if (!Float.isFinite(radius) || !Float.isFinite(far) || radius <= 0 || distance <= radius + .01F || far < distance + radius)
            throw new IllegalArgumentException("Point source must lie outside the enclosed receiver sphere");
        float halfAngle = (float) Math.asin(radius / distance);
        Vector3f direction = new Vector3f(center).sub(eye).normalize();
        return new ShadowLightView(new Matrix4f().perspective(halfAngle * 2, 1, .01F, far)
                .mul(new Matrix4f().lookAt(eye, center, up(direction))), false, eye);
    }

    /** Near point sources need six real faces; a single cone cannot cover receivers surrounding the source. */
    public static ShadowLightView pointFace(float lx, float ly, float lz, int axis, int sign, float far) {
        if (axis < 0 || axis > 2 || Math.abs(sign) != 1 || !Float.isFinite(far) || far <= .01F)
            throw new IllegalArgumentException("Point-light cube face and positive extent required");
        Vector3f eye = point(lx, ly, lz);
        Vector3f direction = new Vector3f(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
        return new ShadowLightView(new Matrix4f().perspective((float) (Math.PI / 2), 1, .01F, far)
                .mul(new Matrix4f().lookAt(eye, new Vector3f(eye).add(direction), up(direction))), false, eye);
    }

    private static Vector3f direction(float x, float y, float z) {
        Vector3f vector = point(x, y, z);
        if (Math.abs(vector.lengthSquared() - 1) > 1.0E-5) throw new IllegalArgumentException("Unit light direction required");
        return vector;
    }
    private static Vector3f point(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("Finite light point required");
        return new Vector3f(x, y, z);
    }
    private static Vector3f up(Vector3f direction) {
        return Math.abs(direction.y) > .95 ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
    }
    public float[] matrix() { return projectionView.get(new float[16]); }
    public float[] inverseMatrix() { return new Matrix4f(projectionView).invert().get(new float[16]); }
    public boolean directional() { return directional; }
    public float[] lightVector() { return new float[] {light.x, light.y, light.z}; }

    /** Eight exact frustum corners for loaded-section coverage and draw culling. */
    public float[][] corners() {
        Matrix4f inverse = new Matrix4f(projectionView).invert();
        float[][] corners = new float[8][3];
        for (int i = 0; i < 8; i++) {
            Vector4f p = inverse.transform(new Vector4f((i & 1) == 0 ? -1 : 1,
                    (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1, 1));
            if (p.w == 0 || !Float.isFinite(p.w)) throw new IllegalStateException("Light frustum reaches infinity");
            corners[i] = new float[] {p.x / p.w, p.y / p.w, p.z / p.w};
        }
        return corners;
    }
}

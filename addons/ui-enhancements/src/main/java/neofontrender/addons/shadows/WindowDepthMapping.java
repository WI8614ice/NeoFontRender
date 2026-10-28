package neofontrender.addons.shadows;

/** Actual GL window-depth and clip-control convention for a captured attachment. */
public record WindowDepthMapping(double near, double far, double clear,
                                 boolean zeroToOne, boolean upperLeft) {
    public WindowDepthMapping {
        if (!unit(near) || !unit(far) || !unit(clear) || near == far)
            throw new IllegalArgumentException("finite nondegenerate GL window-depth mapping required");
    }
    private static boolean unit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }

    public ClipPoint toClip(float u, float v, float depth) {
        if (!Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(depth))
            throw new IllegalArgumentException("finite window sample required");
        double normalized = (depth - near) / (far - near);
        return new ClipPoint(2 * u - 1, upperLeft ? 1 - 2 * v : 2 * v - 1,
                (float) (zeroToOne ? normalized : 2 * normalized - 1));
    }
    public record ClipPoint(float x, float y, float z) {}
}

package neofontrender.addons.api.flight;

import java.util.Objects;

/** Immutable render-only body capsule and eye from the pose provider's same world-space sample. */
public final class FlightBodyGeometry {
    public final FlightVector start, end, eye;
    public final double radius;

    public FlightBodyGeometry(FlightVector start, FlightVector end, double radius, FlightVector eye) {
        this.start = finite(start);
        this.end = finite(end);
        this.eye = finite(eye);
        if (!Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("finite nonnegative radius required");
        this.radius = radius;
    }

    /** Visual proximity only; this does not authorize physics or replace the owner's collision queries. */
    public double signedDistance(FlightVector point) {
        finite(point);
        double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        double length = Math.hypot(Math.hypot(dx, dy), dz);
        double along = length == 0 ? 0 : Math.max(0, Math.min(length,
                (point.x - start.x) * (dx / length) + (point.y - start.y) * (dy / length)
                        + (point.z - start.z) * (dz / length)));
        double t = length == 0 ? 0 : along / length;
        return Math.hypot(Math.hypot(point.x - (start.x + dx * t), point.y - (start.y + dy * t)),
                point.z - (start.z + dz * t)) - radius;
    }

    private static FlightVector finite(FlightVector value) {
        Objects.requireNonNull(value);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
            throw new IllegalArgumentException("finite world point required");
        return value;
    }
}

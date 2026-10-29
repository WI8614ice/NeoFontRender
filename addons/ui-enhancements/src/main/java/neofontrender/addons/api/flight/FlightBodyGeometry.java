package neofontrender.addons.api.flight;

import java.util.Objects;

/** Immutable render-only body geometry and eye from the pose provider's same world-space sample. */
public final class FlightBodyGeometry {
    public enum Shape { CAPSULE, BOX }
    public final Shape shape;
    /** Capsule endpoints/radius; for BOX these are the center/zero, and signedDistance uses the actual box. */
    public final FlightVector start, end, eye;
    private final FlightVector[] halfEdges;
    public final double radius;

    public FlightBodyGeometry(FlightVector start, FlightVector end, double radius, FlightVector eye) {
        shape = Shape.CAPSULE;
        halfEdges = null;
        this.start = finite(start);
        this.end = finite(end);
        this.eye = finite(eye);
        if (!Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("finite nonnegative radius required");
        this.radius = radius;
    }

    /** Actual accepted Cardinal bounds, transformed by the same reference transition as the body. */
    public static FlightBodyGeometry box(FlightVector center, FlightVector halfX, FlightVector halfY,
                                         FlightVector halfZ, FlightVector eye) {
        return new FlightBodyGeometry(center, new FlightVector[] {halfX, halfY, halfZ}, eye);
    }

    private FlightBodyGeometry(FlightVector center, FlightVector[] edges, FlightVector eye) {
        shape = Shape.BOX;
        start = end = finite(center);
        this.eye = finite(eye);
        radius = 0;
        halfEdges = edges.clone();
        for (int i = 0; i < halfEdges.length; i++) {
            var edge = finite(halfEdges[i]);
            double length = Math.hypot(Math.hypot(edge.x, edge.y), edge.z);
            if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("positive finite box half-edge required");
            for (int j = 0; j < i; j++) {
                var other = halfEdges[j];
                double otherLength = Math.hypot(Math.hypot(other.x, other.y), other.z);
                if (Math.abs(edge.scale(1 / length).dot(other.scale(1 / otherLength))) > 1.0e-9)
                    throw new IllegalArgumentException("orthogonal box half-edges required");
            }
        }
    }

    /** Visual proximity only; this does not authorize physics or replace the owner's collision queries. */
    public double signedDistance(FlightVector point) {
        finite(point);
        if (shape == Shape.BOX) {
            var relative = new FlightVector(point.x - start.x, point.y - start.y, point.z - start.z);
            double[] q = new double[3];
            for (int i = 0; i < 3; i++) {
                var edge = halfEdges[i];
                double length = Math.hypot(Math.hypot(edge.x, edge.y), edge.z);
                q[i] = Math.abs(relative.dot(edge.scale(1 / length))) - length;
            }
            return Math.hypot(Math.hypot(Math.max(q[0], 0), Math.max(q[1], 0)), Math.max(q[2], 0))
                    + Math.min(Math.max(q[0], Math.max(q[1], q[2])), 0);
        }
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

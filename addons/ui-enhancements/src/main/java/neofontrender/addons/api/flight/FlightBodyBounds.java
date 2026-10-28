package neofontrender.addons.api.flight;

/** Owner-sampled accepted world bounds, before visual quaternion interpolation. */
public record FlightBodyBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public FlightBodyBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX >= maxX || minY >= maxY || minZ >= maxZ)
            throw new IllegalArgumentException("Finite positive accepted body bounds required");
    }
}

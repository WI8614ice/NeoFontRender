package neofontrender.addons.api.camera;

/** Immutable typed picking query derived from a camera frame. */
public final class CameraPickingRequest {
    private final CameraVector origin, direction;
    private final double distance;
    private final CameraPickingPurpose purpose;
    private final boolean fluids, entities;
    private final float partialTicks;
    public CameraPickingRequest(CameraVector origin, CameraVector direction, double distance,
                                CameraPickingPurpose purpose, boolean fluids, boolean entities) {
        this(origin, direction, distance, purpose, fluids, entities, 0.0F);
    }
    public CameraPickingRequest(CameraVector origin, CameraVector direction, double distance,
                                CameraPickingPurpose purpose, boolean fluids, boolean entities,
                                float partialTicks) {
        if (!Float.isFinite(partialTicks) || partialTicks < 0.0F || partialTicks > 1.0F) {
            throw new IllegalArgumentException("partialTicks must be finite and in [0, 1]");
        }
        this.partialTicks = partialTicks;
        this.origin = origin == null ? new CameraVector(0, 0, 0) : origin;
        this.direction = CameraRay.normalizedDirection(direction);
        this.distance = Math.max(0.0D, Double.isFinite(distance) ? distance : 0.0D);
        this.purpose = purpose == null ? CameraPickingPurpose.MEASUREMENT : purpose;
        this.fluids = fluids; this.entities = entities;
    }
    public CameraVector origin() { return origin; }
    public CameraVector direction() { return direction; }
    public double distance() { return distance; }
    public CameraPickingPurpose purpose() { return purpose; }
    public boolean includeFluids() { return fluids; }
    public boolean includeEntities() { return entities; }
    public float partialTicks() { return partialTicks; }
}

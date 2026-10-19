package neofontrender.addons.api.camera;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.Objects;

/** One immutable presentation-only entity picking decision. It never changes native physics. */
public final class CameraEntityPickGeometry {
    private final Entity entity;
    private final AxisAlignedBB bounds;

    public CameraEntityPickGeometry(Entity entity, AxisAlignedBB bounds) {
        this.entity = Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(bounds, "bounds");
        if (!Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY)
                || !Double.isFinite(bounds.minZ) || !Double.isFinite(bounds.maxX)
                || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)) {
            throw new IllegalArgumentException("entity pick bounds must be finite");
        }
        this.bounds = bounds;
    }

    private CameraEntityPickGeometry(Entity entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.bounds = null;
    }

    /** Explicitly excludes an owned entity whose accepted geometry is unavailable or stale. */
    public static CameraEntityPickGeometry suppressed(Entity entity) {
        return new CameraEntityPickGeometry(entity);
    }

    public Entity entity() { return entity; }
    /** Null only for an explicit suppression decision. */
    public AxisAlignedBB bounds() { return bounds; }
    public boolean isSuppressed() { return bounds == null; }
}

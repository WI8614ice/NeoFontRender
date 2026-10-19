package neofontrender.addons.api.camera;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.util.List;

/** Supplies one read-only snapshot per pick, with the highest-priority decision winning per entity. */
public interface CameraEntityPickGeometryProvider {
    ResourceLocation id();
    default int priority() { return 0; }

    /**
     * Return an empty list to leave native entities alone. A suppressed entry prevents native
     * fallback for a known owner; a bounds entry replaces both broadphase and final ray geometry.
     * The provider must fence its own session, revision and geometry lifetime. No movement
     * authority is acquired. The returned list is copied before any entity intersection occurs.
     */
    List<CameraEntityPickGeometry> sample(World world, Entity excluded, float partialTicks);
}

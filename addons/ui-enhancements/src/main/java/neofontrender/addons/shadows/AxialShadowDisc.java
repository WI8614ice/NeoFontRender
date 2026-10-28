package neofontrender.addons.shadows;

import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.PlayerShadowMode;

/** Contact approximation tied to accepted physical support, independent of interpolated visual Q. */
public record AxialShadowDisc(float x, float y, float z, float upX, float upY, float upZ,
                              float radius, float maxDrop) {
    public AxialShadowDisc {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !((Math.abs(upX) == 1 && upY == 0 && upZ == 0)
                || (upX == 0 && Math.abs(upY) == 1 && upZ == 0)
                || (upX == 0 && upY == 0 && Math.abs(upZ) == 1))
                || !Float.isFinite(radius) || radius <= 0 || !Float.isFinite(maxDrop) || maxDrop <= 0)
            throw new IllegalArgumentException("Finite disc on an accepted cardinal axis required");
    }

    /** Missing accepted geometry is unavailable; a vanilla remote box is not an acceptable substitute. */
    public static AxialShadowDisc from(PlayerShadowMode mode, FlightBodyPose pose,
                                       double originX, double originY, double originZ, float maxDrop) {
        if (!PlayerShadowDrawPolicy.emitsAxialDisc(mode, pose)) return null;
        var bounds = pose.shadowBounds;
        if (bounds == null) return null;
        double hx = (bounds.maxX() - bounds.minX()) * .5;
        double hy = (bounds.maxY() - bounds.minY()) * .5;
        double hz = (bounds.maxZ() - bounds.minZ()) * .5;
        if (hx <= 0 || hy <= 0 || hz <= 0) throw new IllegalArgumentException("Positive accepted player bounds required");
        var up = pose.shadowAxisUp;
        double along = Math.abs(up.x) * hx + Math.abs(up.y) * hy + Math.abs(up.z) * hz;
        double radius = up.x != 0 ? Math.max(hy, hz) : up.y != 0 ? Math.max(hx, hz) : Math.max(hx, hy);
        return new AxialShadowDisc((float) ((bounds.minX() + hx - originX) - up.x * along),
                (float) ((bounds.minY() + hy - originY) - up.y * along),
                (float) ((bounds.minZ() + hz - originZ) - up.z * along),
                (float) up.x, (float) up.y, (float) up.z, (float) radius, maxDrop);
    }

    public ShadowCompositePass.Region receiverRegion() {
        return new ShadowCompositePass.Region(x - upX * maxDrop * .5F, y - upY * maxDrop * .5F,
                z - upZ * maxDrop * .5F, (float) Math.hypot(radius, maxDrop * .5F));
    }

    /** A local axial occlusion view is not presented as a sun or block-light direction. */
    public ShadowLightView occlusionView() {
        var region = receiverRegion();
        return ShadowLightView.directional(region.x(), region.y(), region.z(), region.radius(),
                upX, upY, upZ, region.radius() + .05F);
    }
}

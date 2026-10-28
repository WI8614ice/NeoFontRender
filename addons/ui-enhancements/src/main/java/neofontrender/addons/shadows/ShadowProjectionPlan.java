package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.List;

/** Local receiver region around the actual frozen model, with an independently covered path to the real source. */
record ShadowProjectionPlan(ShadowCompositePass.Region region, List<ShadowLightView> views) {
    ShadowProjectionPlan { views = List.copyOf(views); }

    static ShadowCompositePass.Region region(PlayerShadowCapture.Caster caster) {
        if (caster.availability() != PlayerShadowCapture.Availability.READY || caster.parts().isEmpty())
            throw new IllegalArgumentException("Frozen real player geometry required");
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (var part : caster.parts()) {
            x0 = Math.min(x0, part.minX); y0 = Math.min(y0, part.minY); z0 = Math.min(z0, part.minZ);
            x1 = Math.max(x1, part.maxX); y1 = Math.max(y1, part.maxY); z1 = Math.max(z1, part.maxZ);
        }
        float radius = (float) Math.sqrt(square((x1 - x0) * .5) + square((y1 - y0) * .5) + square((z1 - z0) * .5));
        // This is a local player-shadow effect, not a rewrite of every world's distant shadow map.
        return new ShadowCompositePass.Region((x0 + x1) * .5F, (y0 + y1) * .5F, (z0 + z1) * .5F, radius + 4);
    }

    static ShadowProjectionPlan create(ShadowCompositePass.Region region, ShadowMainLight.Candidate light,
                                       TerrainSceneFrame.Projection origin) {
        if (light.directional()) {
            // Both the selected sun and moon point above the horizon. Put the source plane above all
            // possible terrain, not just above a guessed local roof; coverage still requires loaded geometry.
            if (light.y() <= 0) throw new IllegalArgumentException("Sky light must be above the physical horizon");
            double distance = Math.max(region.radius() + .05,
                    (256 - (origin.originY + region.y()) + region.radius()) / light.y() + region.radius());
            return new ShadowProjectionPlan(region, List.of(ShadowLightView.directional(region.x(), region.y(), region.z(),
                    region.radius(), (float) light.x(), (float) light.y(), (float) light.z(), (float) distance)));
        }
        float lx = (float) (light.x() - origin.originX), ly = (float) (light.y() - origin.originY),
                lz = (float) (light.z() - origin.originZ);
        float distance = (float) Math.sqrt(square(lx - region.x()) + square(ly - region.y()) + square(lz - region.z()));
        float far = distance + region.radius() + .05F;
        if (distance > region.radius() + .01F)
            return new ShadowProjectionPlan(region, List.of(ShadowLightView.point(lx, ly, lz,
                    region.x(), region.y(), region.z(), region.radius(), far)));
        var faces = new ArrayList<ShadowLightView>();
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[] {-1, 1})
            faces.add(ShadowLightView.pointFace(lx, ly, lz, axis, sign, far));
        return new ShadowProjectionPlan(region, faces);
    }

    private static double square(double v) { return v * v; }
}

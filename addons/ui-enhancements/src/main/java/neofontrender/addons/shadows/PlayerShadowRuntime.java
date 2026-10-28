package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.world.World;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.PlayerShadowMode;
import neofontrender.addons.ui.NfrUiEnhancements;

/** Render-thread owner of current scene leases, reusable targets and bounded terrain warming. */
public final class PlayerShadowRuntime implements IResourceManagerReloadListener {
    public static final PlayerShadowRuntime INSTANCE = new PlayerShadowRuntime();
    private ShadowLightDepthPass depth;
    private ShadowCompositePass composite;
    private World world;
    private PlayerShadowMode mode;
    private boolean failed;
    private boolean packOwned;
    private int buildsRemaining;
    private String reported;

    private PlayerShadowRuntime() {}

    public void beforeEntities() {
        var frame = TerrainSceneFrame.current();
        if (frame == null) return;
        if (frame.availability() == TerrainSceneFrame.Availability.NESTED_WORLD_PASS) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            boolean pack = frame.availability() == TerrainSceneFrame.Availability.SHADER_PACK_OWNS_SHADOWS;
            if (world != mc.world || mode != frame.shadowMode() || packOwned != pack) {
                try { release(); }
                finally { world = mc.world; mode = frame.shadowMode(); packOwned = pack; }
            }
            if (failed) return;
            if (frame.availability() != TerrainSceneFrame.Availability.READY) {
                report("FRAME_UNAVAILABLE " + frame.availability()); return;
            }
            buildsRemaining = 1; // CPU mesh warming only; never limits player movement or collision queries.
            var result = TerrainDepthSnapshot.capture(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN);
            if (result.sample() == null) report("OPAQUE_DEPTH_UNAVAILABLE " + result.availability() + " " + result.detail());
        } catch (RuntimeException failure) { fail("capture opaque scene depth", failure); }
    }

    public void afterEntities() {
        var frame = TerrainSceneFrame.current();
        if (failed || frame == null || frame.availability() != TerrainSceneFrame.Availability.READY
                || TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN) == null) return;
        try {
            var foreground = TerrainDepthSnapshot.capture(TerrainDepthSnapshot.Layer.FOREGROUND);
            if (foreground.sample() == null) {
                report("FOREGROUND_DEPTH_UNAVAILABLE " + foreground.availability() + " " + foreground.detail()); return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player != null) PlayerShadowCapture.includeUnrenderedSubject(mc.player);
            if (frame.shadowMode() != PlayerShadowMode.AXIAL_DISC) {
                report("REAL_PROJECTION_CONTROLLER_INCOMPLETE"); return;
            }
            if (depth == null) depth = ShadowLightDepthPass.create(256);
            if (composite == null) composite = ShadowCompositePass.create();
            var union = composite.begin(TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN), foreground.sample());
            int atlas = mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).getGlTextureId();
            String unavailable = null;
            int rendered = 0;
            for (var subject : PlayerShadowCapture.currentSubjects()) {
                var origin = frame.projection();
                var disc = AxialShadowDisc.from(frame.shadowMode(), subject.pose(),
                        origin.originX, origin.originY, origin.originZ, 2);
                if (disc == null) continue;
                // Render only an on-screen receiver region. This also avoids warming off-screen terrain.
                var region = disc.receiverRegion();
                if (!ShadowCompositePass.visible(foreground.sample(), region)) continue;
                var sections = warm(disc.occlusionView(), origin);
                if (sections == null) { unavailable = "TERRAIN_WARMING_OR_UNAVAILABLE"; continue; }
                var result = depth.renderDisc(sections, subject, atlas, 2);
                if (result.sample() == null) {
                    unavailable = result.availability() + " " + result.detail(); continue;
                }
                union.accumulate(result.sample(), region, 1);
                rendered++;
            }
            union.apply();
            report(unavailable != null ? "AXIAL_DISC_PARTIAL " + unavailable
                    : rendered == 0 ? "AXIAL_DISC_NO_ELIGIBLE_VISIBLE_SUBJECT" : "AXIAL_DISC_RENDERED");
        } catch (RuntimeException failure) { fail("render player shadow frame", failure); }
    }

    private List<ShadowTerrainCache.Section> warm(ShadowLightView view, TerrainSceneFrame.Projection origin) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (float[] p : view.corners()) for (int a = 0; a < 3; a++) {
            double v = p[a] + (a == 0 ? origin.originX : a == 1 ? origin.originY : origin.originZ);
            min[a] = Math.min(min[a], v); max[a] = Math.max(max[a], v);
        }
        int x0 = section(min[0]), x1 = section(max[0]), z0 = section(min[2]), z1 = section(max[2]);
        int y0 = Math.max(0, section(min[1])), y1 = Math.min(15, section(max[1]));
        var result = new ArrayList<ShadowTerrainCache.Section>();
        boolean ready = true;
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            var key = new ShadowTerrainCache.Key(x, y, z);
            var cached = ShadowTerrainCache.INSTANCE.currentSection(key);
            if (cached == null && buildsRemaining > 0) {
                buildsRemaining--; cached = ShadowTerrainCache.INSTANCE.section(key);
            }
            if (cached == null || cached.availability() != ShadowTerrainCache.Availability.READY) ready = false;
            else result.add(cached);
        }
        return ready ? result : null;
    }

    private static int section(double coordinate) {
        double index = Math.floor(coordinate / 16);
        if (!Double.isFinite(index) || index < Integer.MIN_VALUE || index >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Representable receiver section required");
        return (int) index;
    }

    /** Claim only a current owned receiver stage. Unsupported frames retain their actual owner. */
    public static boolean ownsVanillaShadow(FlightBodyPose pose) {
        var frame = TerrainSceneFrame.current();
        return frame != null && frame.availability() == TerrainSceneFrame.Availability.READY
                && frame.shadowMode() == PlayerShadowMode.AXIAL_DISC
                && TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN) != null
                && pose != null && pose.shadowReference == FlightBodyPose.ShadowReference.AXIAL
                && pose.shadowBounds != null;
    }

    private void report(String status) {
        if (!status.equals(reported)) {
            NfrUiEnhancements.LOGGER.info("Player shadow availability: mode={} state={}", mode, status);
            reported = status;
        }
    }

    private void fail(String operation, RuntimeException failure) {
        failed = true;
        try { closeTargets(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        NfrUiEnhancements.LOGGER.error("Player shadow subsystem stopped for current world/mode: operation="
                + operation + " mode=" + mode, failure);
    }

    private void closeTargets() {
        try { if (depth != null) depth.close(); }
        finally {
            depth = null;
            try { if (composite != null) composite.close(); }
            finally { composite = null; TerrainDepthSnapshot.release(); }
        }
    }

    public void release() {
        try { closeTargets(); }
        finally { world = null; mode = null; failed = false; reported = null; }
    }

    public void unload(World source) { if (world == source) release(); }
    @Override public void onResourceManagerReload(IResourceManager manager) { release(); }
}

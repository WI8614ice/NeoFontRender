package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
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
    private ShadowLightVisibility visibility;
    private PlayerShadowProxy localBody;
    private static final class LightState {
        final ShadowMainLight.Selection selection = new ShadowMainLight.Selection();
    }
    private final Map<UUID, LightState> lights = new LinkedHashMap<>();
    private World world;
    private PlayerShadowMode mode;
    private boolean failed;
    private boolean packOwned;
    private int buildsRemaining;
    private String reported;
    private String warmUnavailable;

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
            if (frame.shadowMode() == PlayerShadowMode.REAL_PLAYER_PROJECTION && mc.player != null
                    && !PlayerShadowCapture.hasCurrentCaster(mc.player.getUniqueID())) {
                if (localBody == null) localBody = PlayerShadowProxy.create();
                localBody.captureMissing(mc.player);
            }
            if (mc.player != null) PlayerShadowCapture.includeUnrenderedSubject(mc.player);
            if (depth == null) depth = ShadowLightDepthPass.create(256);
            if (composite == null) composite = ShadowCompositePass.create();
            var union = composite.begin(TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN), foreground.sample());
            int atlas = mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).getGlTextureId();
            if (frame.shadowMode() == PlayerShadowMode.REAL_PLAYER_PROJECTION) {
                renderReal(frame, foreground.sample(), union, atlas);
                union.apply();
                return;
            }
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
                if (sections == null) { unavailable = warmUnavailable; continue; }
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
        var required = ShadowLightCoverage.required(view, origin);
        warmUnavailable = required.unavailable();
        if (warmUnavailable != null) return null;
        if (required.keys().size() > ShadowTerrainCache.sectionCapacity()) {
            warmUnavailable = "LIGHT_PATH_EXCEEDS_CURRENT_CACHE_CAPACITY sections=" + required.keys().size();
            return null; // Do not evict/rebuild the same unsatisfiable working set every frame.
        }
        warmUnavailable = "TERRAIN_WARMING_OR_UNAVAILABLE";
        var result = new ArrayList<ShadowTerrainCache.Section>();
        boolean ready = true;
        for (var key : required.keys()) {
            var cached = prepareSection(key);
            if (cached == null || cached.availability() != ShadowTerrainCache.Availability.READY) ready = false;
            else result.add(cached);
        }
        return ready ? result : null;
    }

    private ShadowTerrainCache.Section prepareSection(ShadowTerrainCache.Key key) {
        var cached = ShadowTerrainCache.INSTANCE.currentSection(key);
        Minecraft mc = Minecraft.getMinecraft();
        if (cached == null && buildsRemaining > 0 && mc.world.getChunkProvider().getLoadedChunk(key.x(), key.z()) != null) {
            buildsRemaining--; cached = ShadowTerrainCache.INSTANCE.section(key);
        }
        return cached;
    }

    /** Emits real geometry only; an unavailable native model does not turn into a disc or vanilla body. */
    private void renderReal(TerrainSceneFrame.WorldScope frame, TerrainDepthSnapshot.Sample scene,
                             ShadowCompositePass.Frame union, int atlas) {
        if (visibility == null) visibility = ShadowLightVisibility.create(256);
        var currentIds = new HashSet<UUID>();
        String unavailable = null;
        int rendered = 0;
        for (var caster : PlayerShadowCapture.currentCasters()) {
            currentIds.add(caster.playerId());
            if (caster.availability() != PlayerShadowCapture.Availability.READY) {
                unavailable = "MODEL_" + caster.availability(); continue;
            }
            var region = ShadowProjectionPlan.region(caster);
            if (!ShadowCompositePass.visible(scene, region)) continue;
            var origin = frame.projection();
            double wx = origin.originX + region.x(), wy = origin.originY + region.y(), wz = origin.originZ + region.z();
            var emitterSections = new ArrayList<ShadowTerrainCache.Section>();
            boolean complete = true;
            for (int y = Math.max(0, section(wy - 15)); y <= Math.min(15, section(wy + 15)); y++)
                for (int z = section(wz - 15); z <= section(wz + 15); z++)
                    for (int x = section(wx - 15); x <= section(wx + 15); x++) {
                        var section = prepareSection(new ShadowTerrainCache.Key(x, y, z));
                        if (section == null || section.availability() != ShadowTerrainCache.Availability.READY) complete = false;
                        else emitterSections.add(section);
                    }
            var candidates = ShadowMainLight.candidates(Minecraft.getMinecraft().world, frame.partialTicks(), wx, wy, wz, emitterSections);
            if (!complete) unavailable = "BLOCK_SOURCE_REGION_WARMING_OR_UNAVAILABLE";
            if (candidates.skyAvailability() == ShadowMainLight.SkyAvailability.CUSTOM_SKY_UNAVAILABLE)
                unavailable = "CUSTOM_SKY_PROVIDER_UNAVAILABLE";
            var state = lights.computeIfAbsent(caster.playerId(), ignored -> new LightState());
            var ordered = candidates.lights().stream().sorted(Comparator.comparingDouble(ShadowMainLight.Candidate::strength).reversed()
                    .thenComparing(c -> c.id().kind()).thenComparingLong(c -> c.id().block() == null ? 0 : c.id().block().toLong())).toList();
            var choices = state.selection.probes(ordered);
            var evaluated = new ArrayList<ShadowMainLight.Evaluated>();
            for (var light : choices) {
                ShadowMainLight.Visibility proof = ShadowMainLight.Visibility.OCCLUDED;
                for (var view : ShadowProjectionPlan.create(region, light, origin).views()) {
                    var sections = warm(view, origin);
                    if (sections == null) { proof = ShadowMainLight.Visibility.UNAVAILABLE; continue; }
                    var maps = depth.probe(view, sections, caster, atlas, light.id().block());
                    if (maps.sample() == null) { proof = ShadowMainLight.Visibility.UNAVAILABLE; continue; }
                    var tested = visibility.evaluate(maps.sample());
                    if (tested == ShadowMainLight.Visibility.VISIBLE) { proof = tested; break; }
                    if (tested == ShadowMainLight.Visibility.UNAVAILABLE) proof = tested;
                }
                evaluated.add(new ShadowMainLight.Evaluated(light, proof));
            }
            var selected = state.selection.choose(world, world.getTotalWorldTime(), evaluated);
            if (selected == null) {
                unavailable = "NO_CURRENT_VISIBLE_MAIN_LIGHT " + (warmUnavailable == null ? "NO_ELIGIBLE_SOURCE" : warmUnavailable);
                continue;
            }
            boolean any = false;
            for (var view : ShadowProjectionPlan.create(region, selected, origin).views()) {
                var sections = warm(view, origin);
                if (sections == null) { unavailable = "SELECTED_LIGHT_RECEIVER_PATH_UNAVAILABLE " + warmUnavailable; continue; }
                var maps = depth.render(view, sections, caster, atlas, selected.id().block());
                if (maps.sample() == null) { unavailable = maps.availability() + " " + maps.detail(); continue; }
                union.accumulate(maps.sample(), region, (float) selected.strength());
                any = true;
            }
            if (any) rendered++;
        }
        lights.keySet().retainAll(currentIds);
        report(unavailable != null ? "REAL_PROJECTION_PARTIAL " + unavailable
                : rendered == 0 ? "REAL_PROJECTION_NO_VERIFIED_VISIBLE_BODY" : "REAL_PROJECTION_RENDERED");
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
                && TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN) != null
                && pose != null && (frame.shadowMode() == PlayerShadowMode.REAL_PLAYER_PROJECTION
                || pose.shadowReference == FlightBodyPose.ShadowReference.AXIAL && pose.shadowBounds != null);
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
            finally {
                composite = null;
                try { if (visibility != null) visibility.close(); }
                finally {
                    visibility = null;
                    try { if (localBody != null) localBody.close(); }
                    finally { localBody = null; lights.clear(); TerrainDepthSnapshot.release(); }
                }
            }
        }
    }

    public void release() {
        try { closeTargets(); }
        finally { world = null; mode = null; failed = false; reported = null; }
    }

    public void unload(World source) { if (world == source) release(); }
    @Override public void onResourceManagerReload(IResourceManager manager) { release(); }
}

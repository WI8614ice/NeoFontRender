package neofontrender.addons.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.EntityLivingBase;
import neofontrender.addons.camera.CameraRuntime;
import neofontrender.addons.flight.PlayerBodyRenderScope;
import neofontrender.addons.ui.NfrUiEnhancements;

/** Read-only, bounded observations of real YSM calls; unknown GPU/controller facts stay unknown. */
public final class YsmRenderProbe {
    public interface ModelIdentity { String nfrUi$probeModel(); }
    private static final Set<String> BONES = Set.of("Root", "AllBody", "UpperBody", "AllHead",
            "Head", "Mouth", "mouth2", "mouth3", "mouth4", "mouth5", "Arm", "LeftArm",
            "LeftForeArm", "RightArm", "RightForeArm", "FOX");
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    private static WeakReference<Object> world = new WeakReference<>(null);
    private static UUID playerId;
    private static Boolean flying;
    private static long generation;
    private static int entityId;
    private static int edge, edgeTick, lastTick = Integer.MIN_VALUE;
    private static final Map<String, String> previous = new LinkedHashMap<>();
    private static final Map<String, Integer> captured = new LinkedHashMap<>();

    private YsmRenderProbe() {}

    public static void withRender(EntityLivingBase entity, Object model, float partialTicks,
                                  PlayerBodyRenderScope.RenderCall render) throws Throwable {
        Minecraft mc = Minecraft.getMinecraft();
        if (entity != mc.player || !(entity instanceof AbstractClientPlayer player)) {
            // Fence nested remote/GUI renders from the local player's observation.
            Sample outer = CURRENT.get(); CURRENT.remove();
            try { render.run(); } finally { restore(outer); }
            return;
        }
        PlayerBodyRenderScope.Context pose = PlayerBodyRenderScope.current(entity);
        boolean ownsRoot = pose != null && pose.active();
        int tick = player.ticksExisted;
        if (world.get() != player.world || !player.getUniqueID().equals(playerId) || tick < lastTick || player.getEntityId() != entityId) {
            world = new WeakReference<>(player.world); playerId = player.getUniqueID();
            entityId = player.getEntityId(); generation++;
            flying = null; edge = 0; edgeTick = tick; captured.clear(); previous.clear();
        }
        lastTick = tick;
        if (flying != null && flying != player.capabilities.isFlying) {
            if (edge < 6) {
                edge++; edgeTick = tick; captured.clear();
                for (String cached : previous.values()) {
                    NfrUiEnhancements.LOGGER.info("YSM_PROBE pre_edge={} nextEdge={} snapshot={}",
                            edge - 1, edge, cached);
                }
            }
        }
        flying = player.capabilities.isFlying;
        String pass = (YsmRenderPurpose.usesNativePreview() ? "native_preview" : "world_or_gui")
                + ":view=" + mc.gameSettings.thirdPersonView;
        int offset = tick - edgeTick;
        boolean window = offset == 0 || offset == 1 || offset == 5 || offset == 10;
        // A rolling pre-edge observation is captured at most once per ten ticks per view.
        boolean selected = (edge < 6 || offset <= 10) && (window || tick % 10 == 0)
                && captured.getOrDefault(pass, Integer.MIN_VALUE) != tick
                && (captured.containsKey(pass) || captured.size() < 3);
        Sample outer = CURRENT.get();
        Sample sample = selected ? new Sample(player, model, partialTicks, ownsRoot, pass,
                edge, offset, window) : null;
        if (sample == null) CURRENT.remove(); else { CURRENT.set(sample); captured.put(pass, tick); }
        boolean completed = false;
        try { render.run(); completed = true; }
        finally {
            restore(outer);
            if (sample != null) {
                sample.record.addProperty("renderCompleted", completed);
                String encoded = sample.record.toString();
                if (previous.containsKey(pass) || previous.size() < 3) previous.put(pass, encoded);
                if (sample.emit) NfrUiEnhancements.LOGGER.info("YSM_PROBE {}", encoded);
            }
        }
    }

    private static void restore(Sample sample) {
        if (sample == null) CURRENT.remove(); else CURRENT.set(sample);
    }
    public static boolean wantsBone(String name) {
        Sample sample = CURRENT.get();
        return sample != null && sample.geometry && BONES.contains(name) && !sample.bones.has(name);
    }
    public static void bone(String name, float x, float y, float z,
                            float rx, float ry, float rz, float sx, float sy, float sz,
                            float px, float py, float pz, boolean hidden, boolean cubesHidden,
                            boolean childrenHidden, int cubeCount, String gate, boolean result) {
        Sample sample = CURRENT.get();
        if (sample == null || !sample.geometry || !BONES.contains(name) || sample.bones.has(name)) return;
        JsonObject row = new JsonObject();
        row.add("position", vector(x, y, z)); row.add("rotationRadians", vector(rx, ry, rz));
        row.add("scale", vector(sx, sy, sz)); row.add("pivot", vector(px, py, pz));
        row.addProperty("hidden", hidden); row.addProperty("cubesHidden", cubesHidden);
        row.addProperty("childrenHidden", childrenHidden); row.addProperty("listedCubes", cubeCount);
        row.addProperty("observedGetter", gate); row.addProperty("getterResult", result);
        sample.bones.add(name, row);
    }
    /** Entered around the real mesh-render invocation, after model animation processing. */
    public static void geometry(Runnable render) {
        Sample sample = CURRENT.get();
        if (sample == null) { render.run(); return; }
        boolean outer = sample.geometry;
        sample.geometry = true;
        sample.record.addProperty("geometryInvocationObserved", true);
        try { render.run(); } finally { sample.geometry = outer; }
    }
    public static void requestedAnimation(String animation) {
        Sample sample = CURRENT.get(); if (sample == null) return;
        String key = clip(animation);
        if (sample.requests.has(key)) return;
        if (sample.requests.size() < 16) sample.requests.addProperty(key, true);
        else sample.omittedRequests++;
        sample.record.addProperty("omittedAnimationRequests", sample.omittedRequests);
    }
    /** Called only after the existing root operation returns successfully. */
    public static void rootApplied(boolean flightBridge) {
        Sample sample = CURRENT.get(); if (sample == null) return;
        sample.record.addProperty("rootInvocationObserved", true);
        sample.record.addProperty("rootPath", flightBridge ? "FLIGHT_BRIDGE_GL_CALLS_RETURNED" : "NATIVE_ROOT_CALL_RETURNED");
        if (flightBridge) {
            sample.record.add("appliedRootQuaternionWXYZ", sample.record.get("sampledRootQuaternionWXYZ"));
            sample.record.add("appliedRootWorldOffset", sample.record.get("sampledRootWorldOffset"));
        }
    }
    public static void head(String axis, float nativeValue, float appliedValue) {
        Sample sample = CURRENT.get(); if (sample == null) return;
        sample.record.addProperty("head_" + axis + "_nativeDegrees", nativeValue);
        sample.record.addProperty("head_" + axis + "_appliedDegrees", appliedValue);
    }
    private static JsonArray vector(double x, double y, double z) {
        JsonArray out = new JsonArray();
        for (double value : new double[]{x, y, z}) {
            if (Double.isFinite(value)) out.add(value); else out.add("NON_FINITE");
        }
        return out;
    }
    public static String clip(String text) {
        if (text == null) return "NULL";
        return text.length() <= 256 ? text : text.substring(0, 256) + "[TRUNCATED]";
    }
    private static final class Sample {
        final JsonObject record = new JsonObject();
        final JsonObject bones = new JsonObject(), requests = new JsonObject();
        final boolean emit;
        int omittedRequests;
        boolean geometry;
        Sample(AbstractClientPlayer player, Object model, float partialTicks, boolean root,
               String pass, int edge, int offset, boolean emit) {
            this.emit = emit;
            record.addProperty("schema", 1); record.addProperty("clientTick", player.ticksExisted);
            record.addProperty("worldGeneration", generation);
            record.addProperty("clientWorldIdentity", System.identityHashCode(player.world));
            record.addProperty("dimension", player.dimension);
            record.addProperty("entityId", player.getEntityId());
            record.addProperty("edge", edge); record.addProperty("offsetTicks", offset);
            record.addProperty("pass", pass); record.addProperty("partialTicks", partialTicks);
            record.addProperty("gState", "NOT_OBSERVED_CORRELATE_UG_CLIENT_TICK");
            record.addProperty("bridgeOwnsRoot", root);
            record.addProperty("geometryInvocationObserved", false);
            record.addProperty("rootInvocationObserved", false);
            record.addProperty("rootPath", "NOT_OBSERVED");
            PlayerBodyRenderScope.Context context = PlayerBodyRenderScope.current(player);
            if (context != null && context.active()) {
                var attitude = context.transform().ysmRootAttitude();
                JsonArray quaternion = new JsonArray();
                quaternion.add(attitude.w); quaternion.add(attitude.x);
                quaternion.add(attitude.y); quaternion.add(attitude.z);
                record.add("sampledRootQuaternionWXYZ", quaternion);
                var delta = context.transform().rootWorldOffset();
                record.add("sampledRootWorldOffset", vector(delta.x, delta.y, delta.z));
            }
            record.addProperty("nativeFlying", player.capabilities.isFlying);
            record.addProperty("nativeOnGround", player.onGround);
            record.addProperty("elytra", player.isElytraFlying());
            record.addProperty("riding", player.isRiding()); record.addProperty("sneaking", player.isSneaking());
            record.addProperty("water", player.isInWater());
            record.addProperty("mainHandEmpty", player.getHeldItemMainhand().isEmpty());
            record.addProperty("offHandEmpty", player.getHeldItemOffhand().isEmpty());
            record.add("motion", vector(player.motionX, player.motionY, player.motionZ));
            record.add("pitchHeadYawBodyYaw", vector(player.rotationPitch, player.rotationYawHead, player.renderYawOffset));
            record.add("limbSwingAndAmount", vector(player.limbSwing, player.limbSwingAmount, player.prevLimbSwingAmount));
            record.addProperty("modelResources", model instanceof ModelIdentity identity
                    ? identity.nfrUi$probeModel() : "NOT_OBSERVED");
            record.addProperty("scopedAlpha", CameraRuntime.isShoulderTransparencyRenderActive()
                    ? CameraRuntime.shoulderPlayerAlpha() : 1.0F);
            record.addProperty("animationEvidence", "REQUESTED_ONLY_FINAL_CURRENT_NOT_OBSERVED");
            record.addProperty("boneEvidence", "NATIVE_VISIBILITY_GETTER_CALL_NOT_VERTEX_SUBMISSION");
            record.addProperty("missingBoneMeaning", "NOT_OBSERVED_NOT_ZERO");
            record.addProperty("submittedQuads", "NOT_OBSERVED");
            record.add("requestedAnimations", requests); record.add("observedBones", bones);
        }
    }
}

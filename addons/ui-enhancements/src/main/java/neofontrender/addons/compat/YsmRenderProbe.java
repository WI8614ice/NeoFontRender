package neofontrender.addons.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.EntityLivingBase;
import neofontrender.addons.camera.CameraRuntime;
import neofontrender.addons.api.flight.FlightRenderDiagnostics;
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
    private record GravityKey(String identity, boolean requested, Boolean acknowledged, boolean effective) { }
    private static GravityKey gravity;
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
            gravity = null; edge = 0; edgeTick = tick; captured.clear(); previous.clear();
        }
        lastTick = tick;
        var purpose = YsmRenderPurpose.currentPurpose();
        var observation = FlightRenderDiagnostics.observe(player, player.world, tick);
        if (purpose == YsmRenderPurpose.Purpose.WORLD && observation != null) {
            var currentGravity = new GravityKey(observation.gravityIdentity(), observation.requestedG(),
                    observation.acknowledgedG(), observation.effectiveG());
            if (gravity != null && !Objects.equals(gravity, currentGravity)) {
                edge++; edgeTick = tick; captured.clear();
                for (String cached : previous.values()) {
                    NfrUiEnhancements.LOGGER.info("YSM_PROBE pre_edge={} nextEdge={} snapshot={}",
                            edge - 1, edge, cached);
                }
            }
            if (gravity == null) { edgeTick = tick; captured.clear(); }
            gravity = currentGravity;
        }
        int view = mc.gameSettings.thirdPersonView;
        String pass = purpose.name() + ":view=" + (view >= 0 && view <= 2 ? Integer.toString(view) : "OTHER");
        int offset = tick - edgeTick;
        boolean window = offset == 0 || offset == 1 || offset == 5 || offset == 10;
        // A rolling pre-edge observation is captured at most once per ten ticks per view.
        // Four purposes times four view buckets: previews cannot exhaust the world's slots.
        boolean selected = (window || tick % 10 == 0)
                && captured.getOrDefault(pass, Integer.MIN_VALUE) != tick
                && (captured.containsKey(pass) || captured.size() < 16);
        Sample outer = CURRENT.get();
        Sample sample = selected ? new Sample(player, model, partialTicks, ownsRoot, pass,
                edge, offset, window || purpose == YsmRenderPurpose.Purpose.WORLD, observation) : null;
        if (sample == null) CURRENT.remove(); else { CURRENT.set(sample); captured.put(pass, tick); }
        boolean completed = false;
        try { render.run(); completed = true; }
        finally {
            restore(outer);
            if (sample != null) {
                sample.record.addProperty("renderCompleted", completed);
                String encoded = sample.record.toString();
                if (previous.containsKey(pass) || previous.size() < 16) previous.put(pass, encoded);
                if (sample.emit) NfrUiEnhancements.LOGGER.info("YSM_PROBE {}", encoded);
            }
        }
    }

    private static void restore(Sample sample) {
        if (sample == null) CURRENT.remove(); else CURRENT.set(sample);
    }
    public static boolean observing() { return CURRENT.get() != null; }

    /** This is the field value at this normal process return, not an inferred requested animation. */
    public static void controllerReturned(Object controller, String animation, String state) {
        Sample sample = CURRENT.get();
        if (sample == null) return;
        String key = controller.getClass().getName() + ":" + System.identityHashCode(controller);
        JsonObject row = sample.controllers.has(key) ? sample.controllers.getAsJsonObject(key) : null;
        if (row == null) {
            if (sample.controllers.size() >= 16) {
                sample.record.addProperty("omittedControllerReturns", ++sample.omittedControllerReturns);
                return;
            }
            row = new JsonObject();
            row.addProperty("normalReturns", 0L);
            sample.controllers.add(key, row);
        }
        row.addProperty("normalReturns", row.get("normalReturns").getAsLong() + 1);
        row.addProperty("animationAtProcessReturn", clip(animation));
        row.addProperty("stateAtProcessReturn", clip(state));
        sample.record.addProperty("animationEvidence", "CONTROLLER_FIELDS_AT_NORMAL_PROCESS_RETURN_NOT_GPU_FINAL");
    }

    public static void withMeshBone(String name, int identity, PlayerBodyRenderScope.RenderCall render) throws Throwable {
        Sample sample = CURRENT.get();
        if (sample == null || !sample.geometry) { render.run(); return; }
        MeshObservation outer = sample.mesh;
        MeshObservation current = null;
        String key = name + ":" + identity;
        if (BONES.contains(name)) {
            current = sample.meshRows.get(key);
            if (current == null && sample.meshRows.size() < 32) {
                current = new MeshObservation(name, identity);
                sample.meshRows.put(key, current);
                sample.meshBones.add(key, current.row);
            }
            if (current == null) sample.record.addProperty("omittedMeshBoneCalls", ++sample.omittedMeshBoneCalls);
        }
        sample.mesh = current;
        if (current != null) current.increment("boneCalls");
        boolean completed = false;
        try { render.run(); completed = true; }
        finally {
            if (current != null && completed) current.increment("boneNormalReturns");
            sample.mesh = outer;
        }
    }

    public static void meshInvocation(String phase, PlayerBodyRenderScope.RenderCall render) throws Throwable {
        Sample sample = CURRENT.get();
        MeshObservation mesh = sample == null ? null : sample.mesh;
        if (mesh != null) mesh.increment(phase + "Calls");
        boolean completed = false;
        try { render.run(); completed = true; }
        finally { if (mesh != null && completed) mesh.increment(phase + "NormalReturns"); }
    }

    public static void vertexColor(float red, float green, float blue, float alpha) {
        Sample sample = CURRENT.get();
        MeshObservation mesh = sample == null ? null : sample.mesh;
        if (mesh == null) return;
        mesh.increment("colorNormalReturns");
        JsonArray color = vector(red, green, blue);
        if (Float.isFinite(alpha)) color.add(alpha); else color.add("NON_FINITE");
        mesh.row.add("lastReturnedVertexColorRGBA", color);
    }

    public static void vertexEnded() {
        Sample sample = CURRENT.get();
        if (sample != null && sample.mesh != null) sample.mesh.increment("endVertexNormalReturns");
    }

    private static final class MeshObservation {
        final JsonObject row = new JsonObject();
        MeshObservation(String name, int identity) {
            row.addProperty("boneName", name); row.addProperty("boneIdentity", identity);
            row.addProperty("evidence", "CPU_BUFFER_CALLS_NOT_GPU_OR_DRAW_COMPLETION");
            for (String counter : new String[]{"boneCalls", "boneNormalReturns", "cubeCalls", "cubeNormalReturns",
                    "quadCalls", "quadNormalReturns", "colorNormalReturns", "endVertexNormalReturns"}) row.addProperty(counter, 0L);
        }
        void increment(String name) { row.addProperty(name, row.get(name).getAsLong() + 1); }
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
        final JsonObject controllers = new JsonObject(), meshBones = new JsonObject();
        final Map<String, MeshObservation> meshRows = new LinkedHashMap<>();
        MeshObservation mesh;
        long omittedControllerReturns, omittedMeshBoneCalls;
        final boolean emit;
        int omittedRequests;
        boolean geometry;
        Sample(AbstractClientPlayer player, Object model, float partialTicks, boolean root,
               String pass, int edge, int offset, boolean emit, FlightRenderDiagnostics.Observation observation) {
            this.emit = emit;
            record.addProperty("schema", 3); record.addProperty("clientTick", player.ticksExisted);
            record.addProperty("worldGeneration", generation);
            record.addProperty("clientWorldIdentity", System.identityHashCode(player.world));
            record.addProperty("dimension", player.dimension);
            record.addProperty("entityId", player.getEntityId());
            record.addProperty("renderEntityClass", player.getClass().getName());
            record.addProperty("logicalLocalPlayerEntityId", Minecraft.getMinecraft().player.getEntityId());
            record.addProperty("logicalPlayerMatch", "EXACT_INSTANCE");
            record.addProperty("animatableClass", model == null ? "NULL" : model.getClass().getName());
            record.addProperty("animatableIdentity", System.identityHashCode(model));
            record.addProperty("thirdPersonView", Minecraft.getMinecraft().gameSettings.thirdPersonView);
            record.addProperty("edge", edge); record.addProperty("offsetTicks", offset);
            record.addProperty("pass", pass); record.addProperty("partialTicks", partialTicks);
            record.addProperty("edgeTrigger", "WORLD_PROVIDER_GRAVITY_OBSERVATION_CHANGE_NOT_NATIVE_FLYING");
            if (observation == null) {
                record.addProperty("gState", "NOT_OBSERVED_FOR_EXACT_ACTOR_WORLD_TICK");
            } else {
                record.addProperty("gState", "PROVIDER_OBSERVATION");
                record.addProperty("gRequested", observation.requestedG());
                if (observation.acknowledgedG() == null) record.addProperty("gAcknowledged", "NOT_OBSERVED");
                else record.addProperty("gAcknowledged", observation.acknowledgedG());
                record.addProperty("gEffective", observation.effectiveG());
                record.addProperty("gIdentity", observation.gravityIdentity());
                record.addProperty("physicalScope", observation.physicalScope());
                record.addProperty("physicalRevision", observation.physicalRevision());
                record.addProperty("providerClientTick", observation.clientTick());
            }
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
            record.add("controllersAtNormalProcessReturn", controllers);
            record.add("meshBones", meshBones);
        }
    }
}

package neofontrender.addons.camera;

import java.lang.ref.WeakReference;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.renderer.GlStateManager;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import neofontrender.addons.api.camera.CameraFrame;
import neofontrender.addons.api.camera.CameraAttitude;
import neofontrender.addons.api.camera.CameraVector;
import neofontrender.addons.api.flight.FlightRenderDiagnostics;
import neofontrender.addons.ui.NfrUiEnhancements;

/** Samples final render matrices and bounded collision evidence only at the existing diagnostic cadence. */
final class CameraEyeProbe {
    private static final ArrayBlockingQueue<Observation> PENDING = new ArrayBlockingQueue<>(16);
    private static WeakReference<Entity> actor = new WeakReference<>(null);
    private static String scope;
    private static int lastTick = Integer.MIN_VALUE, edgeTick, emitted, windowTick;
    private static boolean horizontalContact, verticalContact, movingHorizontally;
    private static Boolean requestedG, effectiveG;
    private static int perspective;
    private static long dropped;
    private static volatile boolean writerFailed;

    static {
        Thread writer = new Thread(CameraEyeProbe::write, "UIE-camera-eye-diagnostics");
        writer.setDaemon(true);
        writer.start();
    }

    private CameraEyeProbe() { }

    /** Called only after this pass has captured its final world-space camera origin. */
    static void capture(Entity entity, float partialTicks, CameraFrame frame,
                        Vec3d finalEye, int view, boolean nativeOrigin, boolean setupApplied,
                        CameraVector previousBase, CameraVector renderBase, float effectiveEyeHeight,
                        Matrix4f inverseModelView, Vec3d modelViewWorldBase) {
        Minecraft mc = Minecraft.getMinecraft();
        if (writerFailed || entity != mc.player || entity.world != mc.world) return;
        int tick = entity.ticksExisted;
        var physical = FlightRenderDiagnostics.observe(entity, entity.world, tick);
        String observedScope = physical == null ? "UNAVAILABLE" : physical.physicalScope();
        Boolean observedRequestedG = physical == null ? null : physical.requestedG();
        Boolean observedEffectiveG = physical == null ? null : physical.effectiveG();
        boolean observedMotion = entity.motionX != 0.0 || entity.motionZ != 0.0;
        boolean reset = actor.get() != entity || !Objects.equals(scope, observedScope)
                || tick < lastTick;
        if (reset) {
            actor = new WeakReference<>(entity);
            scope = observedScope;
            emitted = 0;
            windowTick = tick;
            edgeTick = tick;
            lastTick = Integer.MIN_VALUE;
        } else if ((entity.collidedHorizontally != horizontalContact
                || entity.collidedVertically != verticalContact || !Objects.equals(observedRequestedG, requestedG)
                || !Objects.equals(observedEffectiveG, effectiveG) || view != perspective
                || observedMotion != movingHorizontally)) {
            // Do not consume an edge during a cooldown: rapid G/contact changes are diagnostic.
            // The per-tick deduplication and scope cap bound even repeated changes.
            edgeTick = tick;
        }
        horizontalContact = entity.collidedHorizontally;
        verticalContact = entity.collidedVertically;
        requestedG = observedRequestedG;
        effectiveG = observedEffectiveG;
        movingHorizontally = observedMotion;
        perspective = view;
        if (tick == lastTick) return;
        lastTick = tick;
        int offset = tick - edgeTick;
        // Renew the finite allowance each minute so startup contacts cannot exhaust a long session.
        // Sustained wall contact has one sample per second after its initial edge window.
        if (tick - windowTick >= 1200) { windowTick = tick; emitted = 0; }
        boolean sustainedContact = horizontalContact && offset > 10 && tick % 20 == 0;
        if (emitted >= 96 || (!sustainedContact && offset != 0 && offset != 1 && offset != 5 && offset != 10)) return;
        emitted++;
        Observation value = new Observation(tick, partialTicks, frame.sampleId(), clip(scope),
                physical == null ? -1 : physical.physicalRevision(),
                physical == null ? "UNAVAILABLE" : clip(physical.gravityIdentity()), requestedG,
                physical == null ? null : physical.acknowledgedG(), effectiveG, view, nativeOrigin, setupApplied, offset,
                entity.collidedHorizontally, entity.collidedVertically, finalEye,
                entity.getEntityBoundingBox(), new Vec3d(entity.posX, entity.posY, entity.posZ),
                previousBase, renderBase, effectiveEyeHeight, frame,
                geometry(entity, finalEye, frame, inverseModelView, modelViewWorldBase), dropped);
        if (!PENDING.offer(value)) dropped++;
    }

    private static String geometry(Entity entity, Vec3d eye, CameraFrame frame,
                                   Matrix4f inverseModelView, Vec3d worldBase) {
        try {
            FloatBuffer projectionValues = BufferUtils.createFloatBuffer(16);
            GlStateManager.getFloat(GL11.GL_PROJECTION_MATRIX, projectionValues);
            Matrix4f projection = new Matrix4f();
            StringBuilder values = new StringBuilder();
            for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
                float value = projectionValues.get(column * 4 + row);
                if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite final camera projection");
                projection.setElement(row, column, value);
                if (values.length() > 0) values.append(',');
                values.append(value);
            }
            projection.invert();
            Matrix4f inverseClip = new Matrix4f();
            inverseClip.mul(inverseModelView, projection);
            List<Vec3d> corners = new ArrayList<>(4);
            double minX = eye.x, minY = eye.y, minZ = eye.z, maxX = eye.x, maxY = eye.y, maxZ = eye.z;
            for (int i = 0; i < 4; i++) {
                Vector4f corner = new Vector4f((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, -1, 1);
                inverseClip.transform(corner);
                if (!Float.isFinite(corner.w) || corner.w == 0) throw new IllegalStateException("Invalid near-plane homogeneous coordinate");
                Vec3d point = new Vec3d(worldBase.x + corner.x / corner.w,
                        worldBase.y + corner.y / corner.w, worldBase.z + corner.z / corner.w);
                if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z))
                    throw new IllegalStateException("Non-finite world near-plane corner");
                corners.add(point);
                minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); minZ = Math.min(minZ, point.z);
                maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
            }
            AxisAlignedBB envelope = new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
            String collision;
            // This only caps diagnostic query size; it never limits the camera or player.
            if (maxX - minX > 2 || maxY - minY > 2 || maxZ - minZ > 2) {
                collision = "NOT_QUERIED_ENVELOPE_SPAN_GT_2";
            } else {
                var world = entity.world;
                int tick = entity.ticksExisted;
                var expectedPosition = new Vec3d(entity.posX, entity.posY, entity.posZ);
                var boxes = world.getCollisionBoxes(entity, envelope);
                if (entity.world != world || Minecraft.getMinecraft().world != world
                        || entity.ticksExisted != tick || entity.posX != expectedPosition.x
                        || entity.posY != expectedPosition.y || entity.posZ != expectedPosition.z
                        || !CameraRuntime.diagnosticSampleCurrent(entity, frame))
                    return "finalGeometry=STALE_COLLISION_CALLBACK";
                int eyeInside = 0, cornersInside = 0;
                AxisAlignedBB first = null, firstEye = null, firstCorner = null;
                for (AxisAlignedBB box : boxes) {
                    if (first == null) first = box;
                    if (inside(box, eye)) { eyeInside++; if (firstEye == null) firstEye = box; }
                    for (Vec3d point : corners) if (inside(box, point)) {
                        cornersInside++;
                        if (firstCorner == null) firstCorner = box;
                    }
                }
                collision = "envelopeCandidates=" + boxes.size() + ",eyeInside=" + eyeInside
                        + ",nearCornerInside=" + cornersInside + ",firstEnvelopeCandidate=" + first
                        + ",firstEyeCollider=" + firstEye + ",firstNearCornerCollider=" + firstCorner;
            }
            return "finalEyeMinusFrame=" + eye.subtract(frame.position().x, frame.position().y, frame.position().z)
                    + " projectionRowMajor=" + values + " nearPlaneWorldCorners=" + corners
                    + " nearEnvelope=" + envelope + " collisionEvidence=" + collision
                    + " collisionEvidenceScope=POINTS_AND_ENVELOPE_NOT_COMPLETE_NEAR_PLANE_SAT";
        } catch (RuntimeException failure) {
            NfrUiEnhancements.LOGGER.error("CAMERA_EYE_PROBE final projection/collision observation failed", failure);
            return "finalGeometry=FAILED:" + failure.getClass().getName();
        }
    }

    private static boolean inside(AxisAlignedBB box, Vec3d point) {
        return point.x > box.minX && point.x < box.maxX && point.y > box.minY && point.y < box.maxY
                && point.z > box.minZ && point.z < box.maxZ;
    }

    private static String clip(String value) {
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private static void write() {
        try {
            while (true) {
                Observation value = PENDING.take();
                NfrUiEnhancements.LOGGER.info("CAMERA_EYE_PROBE {}", value);
            }
        } catch (InterruptedException interrupted) {
            writerFailed = true;
            Thread.currentThread().interrupt();
            NfrUiEnhancements.LOGGER.error("CAMERA_EYE_PROBE writer interrupted; capture stopped", interrupted);
        } catch (RuntimeException | Error failure) {
            writerFailed = true;
            // Preserve the original failure on this diagnostic thread; game state is untouched.
            throw failure;
        }
    }

    private record Observation(int clientTick, float partialTick, long frameId, String physicalScope,
            long physicalRevision, String gravityIdentity, Boolean requestedG, Boolean acknowledgedG,
            Boolean effectiveG, int perspective, boolean nativeOrigin, boolean setupApplied,
            int edgeOffset, boolean collidedHorizontally, boolean collidedVertically, Vec3d finalEye,
            AxisAlignedBB installedBodyBounds, Vec3d installedPosition, CameraVector previousBase,
            CameraVector renderBase, float effectiveEyeHeight, CameraFrame frame, String finalGeometry, long droppedBefore) {
        @Override public String toString() {
            return "source=ADOPTED_MODELVIEW_ORIGIN clientTick=" + clientTick + " partialTick=" + partialTick
                    + " frame=" + frameId + " physicalScope=" + physicalScope + " physicalRevision=" + physicalRevision
                    + " gravity=" + gravityIdentity + " g=" + requestedG + "/" + acknowledgedG + "/" + effectiveG
                    + " perspective=" + perspective + " nativeOrigin=" + nativeOrigin + " setupApplied=" + setupApplied
                    + " edgeOffset=" + edgeOffset + " contact=" + collidedHorizontally + "/" + collidedVertically
                    + " finalEye=" + finalEye + " installedBodyBounds=" + installedBodyBounds
                    + " installedPosition=" + installedPosition + " previousBase=" + vector(previousBase)
                    + " renderBase=" + vector(renderBase) + " effectiveEyeHeight="
                    + (Float.isFinite(effectiveEyeHeight) ? effectiveEyeHeight : "NOT_OBSERVED")
                    + " renderBaseTranslatedNativeBounds=" + (renderBase == null ? "NOT_OBSERVED"
                            : installedBodyBounds.offset(renderBase.x - installedPosition.x,
                                    renderBase.y - installedPosition.y, renderBase.z - installedPosition.z))
                    + " framePosition=" + vector(frame.position()) + " frameTarget=" + vector(frame.targetPosition())
                    + " bodyQ=" + quaternion(frame.bodyAttitude()) + " viewQ=" + quaternion(frame.viewAttitude())
                    + " " + finalGeometry + " clipResult=NOT_APPLIED_BY_DIAGNOSTIC targetBlock=NOT_OBSERVED"
                    + " bodyBoundsSource=NATIVE_BOUNDS_TRANSLATED_TO_RENDER_BASE droppedBefore=" + droppedBefore;
        }
        private static String vector(CameraVector value) {
            return value == null ? "NOT_OBSERVED" : value.x + "," + value.y + "," + value.z;
        }
        private static String quaternion(CameraAttitude value) {
            return value.x + "," + value.y + "," + value.z + "," + value.w;
        }
    }
}

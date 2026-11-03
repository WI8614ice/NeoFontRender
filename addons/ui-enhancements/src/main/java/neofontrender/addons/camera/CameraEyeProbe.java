package neofontrender.addons.camera;

import java.lang.ref.WeakReference;
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

/** Observes the adopted modelview origin without issuing camera or collision queries. */
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
                        CameraVector previousBase, CameraVector renderBase, float effectiveEyeHeight) {
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
                previousBase, renderBase, effectiveEyeHeight, frame, dropped);
        if (!PENDING.offer(value)) dropped++;
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
            CameraVector renderBase, float effectiveEyeHeight, CameraFrame frame, long droppedBefore) {
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
                    + " nearPlane=NOT_OBSERVED clipResult=NOT_OBSERVED targetBlock=NOT_OBSERVED"
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

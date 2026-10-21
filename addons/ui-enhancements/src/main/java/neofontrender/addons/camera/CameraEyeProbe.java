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
    private static int lastTick = Integer.MIN_VALUE, edgeTick, emitted;
    private static boolean horizontalContact, verticalContact, requestedG, effectiveG;
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
                        Vec3d finalEye, int view, boolean nativeOrigin, boolean setupApplied) {
        Minecraft mc = Minecraft.getMinecraft();
        if (writerFailed || entity != mc.player || entity.world != mc.world) return;
        int tick = entity.ticksExisted;
        var physical = FlightRenderDiagnostics.observe(entity, entity.world, tick);
        if (physical == null) return;
        boolean reset = actor.get() != entity || !Objects.equals(scope, physical.physicalScope())
                || tick < lastTick;
        if (reset) {
            actor = new WeakReference<>(entity);
            scope = physical.physicalScope();
            emitted = 0;
            edgeTick = tick;
            lastTick = Integer.MIN_VALUE;
        } else if ((entity.collidedHorizontally != horizontalContact
                || entity.collidedVertically != verticalContact || physical.requestedG() != requestedG
                || physical.effectiveG() != effectiveG || view != perspective)) {
            // Do not consume an edge during a cooldown: rapid G/contact changes are diagnostic.
            // The per-tick deduplication and scope cap bound even repeated changes.
            edgeTick = tick;
        }
        horizontalContact = entity.collidedHorizontally;
        verticalContact = entity.collidedVertically;
        requestedG = physical.requestedG();
        effectiveG = physical.effectiveG();
        perspective = view;
        if (tick == lastTick) return;
        lastTick = tick;
        int offset = tick - edgeTick;
        // Finite edge windows, one sample per tick, and a per-scope total cap.
        if (emitted >= 96 || (offset != 0 && offset != 1 && offset != 5 && offset != 10)) return;
        emitted++;
        Observation value = new Observation(tick, partialTicks, frame.sampleId(), clip(scope),
                physical.physicalRevision(), clip(physical.gravityIdentity()), requestedG,
                physical.acknowledgedG(), effectiveG, view, nativeOrigin, setupApplied, offset,
                entity.collidedHorizontally, entity.collidedVertically, finalEye,
                entity.getEntityBoundingBox(), frame, dropped);
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
            long physicalRevision, String gravityIdentity, boolean requestedG, Boolean acknowledgedG,
            boolean effectiveG, int perspective, boolean nativeOrigin, boolean setupApplied,
            int edgeOffset, boolean collidedHorizontally, boolean collidedVertically, Vec3d finalEye,
            AxisAlignedBB installedBodyBounds, CameraFrame frame, long droppedBefore) {
        @Override public String toString() {
            return "source=ADOPTED_MODELVIEW_ORIGIN clientTick=" + clientTick + " partialTick=" + partialTick
                    + " frame=" + frameId + " physicalScope=" + physicalScope + " physicalRevision=" + physicalRevision
                    + " gravity=" + gravityIdentity + " g=" + requestedG + "/" + acknowledgedG + "/" + effectiveG
                    + " perspective=" + perspective + " nativeOrigin=" + nativeOrigin + " setupApplied=" + setupApplied
                    + " edgeOffset=" + edgeOffset + " contact=" + collidedHorizontally + "/" + collidedVertically
                    + " finalEye=" + finalEye + " installedBodyBounds=" + installedBodyBounds
                    + " framePosition=" + vector(frame.position()) + " frameTarget=" + vector(frame.targetPosition())
                    + " bodyQ=" + quaternion(frame.bodyAttitude()) + " viewQ=" + quaternion(frame.viewAttitude())
                    + " nearPlane=NOT_OBSERVED clipResult=NOT_OBSERVED targetBlock=NOT_OBSERVED"
                    + " bodyBoundsSource=INSTALLED_NATIVE_NOT_RENDER_INTERPOLATED droppedBefore=" + droppedBefore;
        }
        private static String vector(CameraVector value) {
            return value.x + "," + value.y + "," + value.z;
        }
        private static String quaternion(CameraAttitude value) {
            return value.x + "," + value.y + "," + value.z + "," + value.w;
        }
    }
}

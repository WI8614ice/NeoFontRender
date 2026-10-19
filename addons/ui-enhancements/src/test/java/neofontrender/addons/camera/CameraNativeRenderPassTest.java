package neofontrender.addons.camera;

import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.Vec3d;
import neofontrender.addons.api.camera.CameraAttitude;
import neofontrender.addons.api.camera.CameraFrame;
import neofontrender.addons.api.camera.CameraVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraNativeRenderPassTest {
    @BeforeAll
    static void initializeRegistries() { Bootstrap.register(); }

    @AfterEach
    void endPass() { CameraRuntime.clearNativeCameraRenderSample(); }

    @Test
    void preCameraFogNeverConsumesThePreviousPassEvenWithTheSamePartial() {
        EntityArmorStand entity = entity();
        for (float nextPartial : new float[]{0.21999407F, 0.5599941F}) {
            completeCameraSetup(entity, 0.5599941F);
            // renderWorldPass calls fog before orientCamera. A new pass must invalidate
            // the old GL/eye sample even when interpolation happens to be identical.
            CameraRuntime.clearNativeCameraRenderSample();
            assertNull(CameraRuntime.alignActiveRenderInfoPosition(entity, nextPartial,
                    new Vec3d(1.0D, 2.0D, 3.0D)));
            completeCameraSetup(entity, nextPartial);
            Vec3d nativePosition = new Vec3d(1.0D, 2.0D, 3.0D);
            Vec3d adjusted = CameraRuntime.alignActiveRenderInfoPosition(entity, nextPartial,
                    nativePosition);
            double remaining = 1.0D - nextPartial;
            assertNotNull(adjusted);
            assertEquals(1.0D + remaining, adjusted.x, 1.0E-6D);
            assertEquals(2.0D + 2.0D * remaining, adjusted.y, 1.0E-6D);
            assertEquals(3.0D + 3.0D * remaining, adjusted.z, 1.0E-6D);
            assertThrows(IllegalStateException.class, () ->
                    CameraRuntime.nativeCameraRenderSample(entity, nextPartial + 0.1F));
            assertThrows(IllegalStateException.class, () ->
                    CameraRuntime.nativeCameraRenderSample(entity(), nextPartial));
        }
    }

    @Test
    void passEndAndNewRenderEntityCannotReuseCapturedAnchor() {
        EntityArmorStand previousEntity = entity();
        completeCameraSetup(previousEntity, 0.5F);
        CameraRuntime.clearNativeCameraRenderSample();
        assertNull(CameraRuntime.nativeCameraRenderSample(entity(), 0.5F));
        assertNull(CameraRuntime.nativeCameraRenderSample(previousEntity, 0.5F));
        completeCameraSetup(entity(), 0.5F);
        CameraRuntime.clearNativeCameraRenderSample();
        assertNull(CameraRuntime.nativeCameraRenderSample(previousEntity, 0.5F));
    }

    private static void completeCameraSetup(EntityArmorStand entity, float partial) {
        CameraVector eye = new CameraVector(8.0D, 9.0D, 10.0D);
        CameraFrame frame = new CameraFrame(1L, partial, CameraAttitude.IDENTITY,
                CameraAttitude.IDENTITY, eye, eye, false);
        CameraRuntime.beginNativeCameraRenderSample(entity, partial, frame, true, true, 0);
        CameraRuntime.captureNativeCameraAnchor(entity, partial, 1.62F);
        CameraRuntime.NativeCameraRenderSample sample =
                CameraRuntime.nativeCameraRenderSample(entity, partial);
        sample.originTranslation();
        sample.cameraSetupApplied = true;
    }

    private static EntityArmorStand entity() {
        EntityArmorStand entity = new EntityArmorStand(null);
        entity.posX = 10.0D; entity.posY = 20.0D; entity.posZ = 30.0D;
        entity.prevPosX = 1.0D; entity.prevPosY = 2.0D; entity.prevPosZ = 3.0D;
        entity.lastTickPosX = 2.0D; entity.lastTickPosY = 4.0D; entity.lastTickPosZ = 6.0D;
        return entity;
    }
}

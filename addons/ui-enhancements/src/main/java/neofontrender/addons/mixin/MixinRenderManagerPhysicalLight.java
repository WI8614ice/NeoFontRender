package neofontrender.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightBodyPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Player light is sampled at the same physical display eye as its pose, not an upright transport offset. */
@Mixin(RenderManager.class)
public abstract class MixinRenderManagerPhysicalLight {
    @WrapOperation(method = {"renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V", "func_188388_a(Lnet/minecraft/entity/Entity;FZ)V"},
            at = {@At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getBrightnessForRender()I"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;func_70070_b()I")},
            remap = false, require = 1, allow = 1)
    private int nfrUi$physicalLight(Entity entity, Operation<Integer> original,
                                   Entity rendered, float partialTicks, boolean outlines) {
        return nfrUi$sampleLight(entity, original, partialTicks);
    }

    @WrapOperation(method = {"renderMultipass(Lnet/minecraft/entity/Entity;F)V", "func_188389_a(Lnet/minecraft/entity/Entity;F)V"},
            at = {@At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getBrightnessForRender()I"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;func_70070_b()I")},
            remap = false, require = 1, allow = 1)
    private int nfrUi$physicalMultipassLight(Entity entity, Operation<Integer> original,
                                            Entity rendered, float partialTicks) {
        return nfrUi$sampleLight(entity, original, partialTicks);
    }

    @Unique
    private static int nfrUi$sampleLight(Entity entity, Operation<Integer> original, float partialTicks) {
        if (!(entity instanceof AbstractClientPlayer) || entity.world != Minecraft.getMinecraft().world)
            return original.call(entity);
        FlightBodyPose pose = FlightApi.queryBodyPose((AbstractClientPlayer) entity, partialTicks);
        if (pose == null || pose.geometry == null) return original.call(entity);
        BlockPos sample = new BlockPos(pose.geometry.eye.x, pose.geometry.eye.y, pose.geometry.eye.z);
        return entity.world.isBlockLoaded(sample) ? entity.world.getCombinedLight(sample, 0) : 0;
    }
}

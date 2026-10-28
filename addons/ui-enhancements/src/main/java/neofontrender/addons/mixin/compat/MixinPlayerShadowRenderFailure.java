package neofontrender.addons.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import neofontrender.addons.shadows.PlayerShadowCapture;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The vanilla catch returns normally; a partial failed model must still be unavailable to shadow consumers. */
@Mixin(RenderLivingBase.class)
public abstract class MixinPlayerShadowRenderFailure {
    @WrapOperation(method = {"doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
            "func_76986_a(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V"},
            at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false),
            remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$rejectPartialModel(Logger logger, String message, Throwable failure, Operation<Void> original) {
        PlayerShadowCapture.modelFailed();
        original.call(logger, message, failure);
    }
}

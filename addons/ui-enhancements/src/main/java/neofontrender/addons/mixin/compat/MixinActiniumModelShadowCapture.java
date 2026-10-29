package neofontrender.addons.mixin.compat;

import neofontrender.addons.compat.ActiniumShadowCompat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.model.ModelRenderer;
import neofontrender.addons.shadows.PlayerShadowCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs before Actinium's cancellable batching hook; neither route is asked to prepare animation again. */
@Mixin(value = ModelRenderer.class, priority = 2000)
public abstract class MixinActiniumModelShadowCapture {
    @Shadow private boolean compiled;
    @Unique private float nfrUi$compiledScale;

    @WrapMethod(method = {"render(F)V", "func_78785_a(F)V", "renderWithRotation(F)V", "func_78791_b(F)V"},
            remap = false, require = 2, expect = 2, allow = 2)
    private void nfrUi$fenceCompiledBuffers(float scale, Operation<Void> original) throws Throwable {
        PlayerShadowCapture.withModelDraw(() -> original.call(scale));
    }

    @Inject(method = "compileDisplayList(F)V", at = @At("RETURN"), require = 1)
    private void nfrUi$rememberCompiledScale(float scale, CallbackInfo ci) { nfrUi$compiledScale = scale; }

    @Inject(method = "render(F)V", at = @At("HEAD"), require = 1)
    private void nfrUi$capturePart(float scale, CallbackInfo ci) {
        if (!PlayerShadowCapture.collectingModel()) return;
        PlayerShadowCapture.modelPart((ModelRenderer) (Object) this,
                ActiniumShadowCompat.useModelRendererBatching() || !compiled ? scale : nfrUi$compiledScale, scale, false);
    }

    @Inject(method = "renderWithRotation(F)V", at = @At("HEAD"), require = 1)
    private void nfrUi$captureRotatedPart(float scale, CallbackInfo ci) {
        if (!PlayerShadowCapture.collectingModel()) return;
        PlayerShadowCapture.modelPart((ModelRenderer) (Object) this,
                ActiniumShadowCompat.useModelRendererBatching() || !compiled ? scale : nfrUi$compiledScale, scale, true);
    }
}

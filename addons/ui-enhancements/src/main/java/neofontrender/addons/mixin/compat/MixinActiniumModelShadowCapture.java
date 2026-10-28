package neofontrender.addons.mixin.compat;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
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

    @Inject(method = "compileDisplayList(F)V", at = @At("RETURN"), require = 1)
    private void nfrUi$rememberCompiledScale(float scale, CallbackInfo ci) { nfrUi$compiledScale = scale; }

    @Inject(method = "render(F)V", at = @At("HEAD"), require = 1)
    private void nfrUi$capturePart(float scale, CallbackInfo ci) {
        PlayerShadowCapture.modelPart((ModelRenderer) (Object) this,
                ActiniumRuntimeOptions.useModelRendererBatching() || !compiled ? scale : nfrUi$compiledScale, scale, false);
    }

    @Inject(method = "renderWithRotation(F)V", at = @At("HEAD"), require = 1)
    private void nfrUi$captureRotatedPart(float scale, CallbackInfo ci) {
        PlayerShadowCapture.modelPart((ModelRenderer) (Object) this,
                ActiniumRuntimeOptions.useModelRendererBatching() || !compiled ? scale : nfrUi$compiledScale, scale, true);
    }
}

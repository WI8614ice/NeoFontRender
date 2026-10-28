package neofontrender.addons.mixin.compat;

import net.minecraft.client.renderer.Tessellator;
import neofontrender.addons.shadows.PlayerShadowCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.BufferBuilder;

/** Freezes only explicitly scoped body/item batches before the reusable bytes are consumed. */
@Mixin(Tessellator.class)
public abstract class MixinPlayerShadowSubmittedBuffer {
    @Shadow public abstract BufferBuilder getBuffer();

    @Inject(method = {"draw()V", "func_78381_a()V"}, at = @At("HEAD"),
            remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$submittedVertices(CallbackInfo ci) {
        PlayerShadowCapture.submittedBuffer(getBuffer());
    }
}

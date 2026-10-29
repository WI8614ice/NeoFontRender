package neofontrender.addons.mixin;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.NVFogDistance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Vanilla's DEFAULT reset writes an NV distance enum into GL_FOG_MODE; correct that exact call. */
@Mixin(targets = "net.minecraft.client.renderer.GlStateManager$Profile$1")
public abstract class MixinDefaultProfileFogDistance {
    @ModifyArgs(method = "apply()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;glFogi(II)V", ordinal = 1),
            require = 1, expect = 1, allow = 1)
    private void nfrUi$restoreDistanceParameter(Args args) {
        if ((int) args.get(0) != GL11.GL_FOG_MODE || (int) args.get(1) != NVFogDistance.GL_EYE_RADIAL_NV)
            throw new IllegalStateException("Unexpected DEFAULT fog distance reset arguments: " + args.get(0) + ", " + args.get(1));
        args.set(0, NVFogDistance.GL_FOG_DISTANCE_MODE_NV);
    }
}

package neofontrender.addons.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.EntityRenderer;
import neofontrender.addons.shadows.TerrainSceneFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps every world/portal pass in its own lease and exposes only the post-cutout receiver stage. */
@Mixin(EntityRenderer.class)
public abstract class MixinActiniumWorldShadowFrame {
    @WrapMethod(method = {"renderWorldPass(IFJ)V", "func_175068_a(IFJ)V"},
            remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$worldFrame(int pass, float partialTicks, long finishTime,
                                  Operation<Void> original) throws Throwable {
        var iris = IrisApi.getInstance();
        try (var ignored = TerrainSceneFrame.beginWorld(partialTicks,
                iris.isShaderPackInUse() || iris.isRenderingShadowPass())) {
            original.call(pass, partialTicks, finishTime);
        }
    }

    @Inject(method = {"renderWorldPass(IFJ)V", "func_175068_a(IFJ)V"}, at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0, shift = At.Shift.BEFORE, remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;func_180446_a(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0, shift = At.Shift.BEFORE, remap = false)
    }, remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$opaqueTerrainComplete(int pass, float partialTicks, long finishTime, CallbackInfo ci) {
        TerrainSceneFrame.opaqueComplete();
    }
}

package neofontrender.addons.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import neofontrender.addons.compat.ActiniumShadowCompat;
import net.minecraft.client.renderer.EntityRenderer;
import neofontrender.addons.shadows.TerrainSceneFrame;
import neofontrender.addons.shadows.PlayerShadowCapture;
import neofontrender.addons.shadows.ShadowTerrainCache;
import neofontrender.addons.shadows.PlayerShadowRuntime;
import net.minecraft.client.Minecraft;
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
        if (!ActiniumShadowCompat.available()) {
            original.call(pass, partialTicks, finishTime);
            return;
        }
        if (TerrainSceneFrame.current() == null) ShadowTerrainCache.INSTANCE.bind(Minecraft.getMinecraft().world);
        try (var frame = TerrainSceneFrame.beginWorld(partialTicks,
                ActiniumShadowCompat.shaderPackOwnsShadows())) {
            PlayerShadowRuntime.INSTANCE.observeRendererBoundary("world pass entry; pass=" + pass);
            try { original.call(pass, partialTicks, finishTime); }
            finally { PlayerShadowCapture.endWorld(frame); }
        }
    }

    @Inject(method = {"renderWorldPass(IFJ)V", "func_175068_a(IFJ)V"}, at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0, shift = At.Shift.BEFORE, remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;func_180446_a(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0, shift = At.Shift.BEFORE, remap = false)
    }, remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$opaqueTerrainComplete(int pass, float partialTicks, long finishTime, CallbackInfo ci) {
        PlayerShadowRuntime.INSTANCE.observeRendererBoundary("opaque terrain complete before depth setup; pass=" + pass);
        TerrainSceneFrame.opaqueComplete();
        PlayerShadowRuntime.INSTANCE.beforeEntities();
    }

    @Inject(method = {"renderWorldPass(IFJ)V", "func_175068_a(IFJ)V"}, at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0, shift = At.Shift.AFTER, remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;func_180446_a(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0, shift = At.Shift.AFTER, remap = false)
    }, remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$entityDepthComplete(int pass, float partialTicks, long finishTime, CallbackInfo ci) {
        PlayerShadowRuntime.INSTANCE.afterEntities();
    }
}

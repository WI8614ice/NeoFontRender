package neofontrender.addons.mixin;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.shadows.PlayerShadowConfig;
import neofontrender.addons.shadows.PlayerShadowDrawPolicy;
import neofontrender.addons.shadows.TerrainSceneFrame;
import neofontrender.addons.shadows.PlayerShadowRuntime;
import neofontrender.addons.flight.PlayerBodyRenderScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels only the shadow entry, preserving the render method's independent fire overlay. */
@Mixin(Render.class)
public abstract class MixinRenderFreePlayerShadow {
    @Inject(method = "renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"),
            cancellable = true, require = 1)
    private void nfrUi$omitFreeDisc(Entity entity, double x, double y, double z,
                                  float alpha, float partialTicks, CallbackInfo ci) {
        var frame = TerrainSceneFrame.current();
        if (entity instanceof AbstractClientPlayer player) {
            var body = PlayerBodyRenderScope.current(player);
            var pose = body == null ? FlightApi.queryBodyPose(player, partialTicks) : body.pose();
            if (PlayerShadowDrawPolicy.suppressesVanillaDisc(frame == null ? PlayerShadowConfig.mode() : frame.shadowMode(), pose)
                    || PlayerShadowRuntime.ownsVanillaShadow(pose)) ci.cancel();
        }
    }
}

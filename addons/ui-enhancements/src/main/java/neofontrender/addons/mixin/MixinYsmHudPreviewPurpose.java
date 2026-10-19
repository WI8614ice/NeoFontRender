package neofontrender.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.player.EntityPlayer;
import neofontrender.addons.compat.YsmRenderPurpose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** The verified HUD call site owns preview purpose for the entire vanilla/YSM render chain. */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.RenderExtraPlayerScreenEvent", remap = false)
abstract class MixinYsmHudPreviewPurpose {
    @WrapOperation(method = "render(Lnet/minecraftforge/client/event/RenderGameOverlayEvent$Text;)V",
            at = @At(value = "INVOKE", target = "Lcom/elfmcys/yesstevemodel/util/RenderUtil;renderPlayerEntity(Lnet/minecraft/entity/player/EntityPlayer;DDFFI)V"),
            require = 1, expect = 1, allow = 1)
    private static void nfrUi$renderHudPreview(EntityPlayer player, double x, double y,
                                               float scale, float yaw, int light,
                                               Operation<Void> original) throws Throwable {
        YsmRenderPurpose.withHudPreview(() -> original.call(player, x, y, scale, yaw, light));
    }
}

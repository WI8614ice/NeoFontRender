package neofontrender.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import neofontrender.addons.compat.YsmRenderPurpose;
import org.spongepowered.asm.mixin.Mixin;

/** The actual world entity render call owns the diagnostic purpose, including nested YSM calls. */
@Mixin(RenderGlobal.class)
abstract class MixinYsmWorldRenderPurpose {
    // Keep exact MCP/SRG selectors: release remapping can remove WrapMethod refmap entries.
    @WrapMethod(method = {
            "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
            "func_180446_a(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V"
    }, remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$worldRenderPurpose(Entity viewEntity, ICamera camera, float partialTicks,
                                           Operation<Void> original) throws Throwable {
        YsmRenderPurpose.withWorldRender(() -> original.call(viewEntity, camera, partialTicks));
    }
}

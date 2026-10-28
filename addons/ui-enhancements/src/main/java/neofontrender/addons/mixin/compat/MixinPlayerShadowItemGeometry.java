package neofontrender.addons.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import neofontrender.addons.shadows.PlayerShadowCapture;
import org.spongepowered.asm.mixin.Mixin;

/** Baked held equipment shares the actual body scope; custom TEISR has no verified geometry contract. */
@Mixin(RenderItem.class)
public abstract class MixinPlayerShadowItemGeometry {
    @WrapMethod(method = {"renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V",
            "func_180454_a(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/IBakedModel;)V"},
            remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$captureBakedItem(ItemStack stack, IBakedModel model, Operation<Void> original) {
        if (!stack.isEmpty() && model.isBuiltInRenderer()) PlayerShadowCapture.unsupportedItemRenderer();
        PlayerShadowCapture.withSubmittedGeometry(() -> original.call(stack, model));
    }
}

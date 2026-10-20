package neofontrender.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.EntityLivingBase;
import neofontrender.addons.compat.YsmRenderPurpose;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla inventory previews are not world samples; an enclosing YSM HUD keeps its HUD tag. */
@Mixin(GuiInventory.class)
abstract class MixinYsmGuiRenderPurpose {
    // Keep exact MCP/SRG selectors: release remapping can remove WrapMethod refmap entries.
    @WrapMethod(method = {
            "drawEntityOnScreen(IIIFFLnet/minecraft/entity/EntityLivingBase;)V",
            "func_147046_a(IIIFFLnet/minecraft/entity/EntityLivingBase;)V"
    }, remap = false, require = 1, expect = 1, allow = 1)
    private static void nfrUi$guiRenderPurpose(int x, int y, int scale, float mouseX, float mouseY,
                                              EntityLivingBase entity, Operation<Void> original) throws Throwable {
        YsmRenderPurpose.withGuiPreview(() -> original.call(x, y, scale, mouseX, mouseY, entity));
    }
}

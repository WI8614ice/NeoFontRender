package neofontrender.addons.mixin.compat;

import net.minecraft.client.renderer.entity.Render;
import neofontrender.addons.shadows.access.PlayerRendererShadowState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Render.class)
public interface AccessorPlayerRendererShadowState extends PlayerRendererShadowState {
    @Override @Accessor("renderOutlines") boolean nfrUi$shadowRenderOutlines();
}

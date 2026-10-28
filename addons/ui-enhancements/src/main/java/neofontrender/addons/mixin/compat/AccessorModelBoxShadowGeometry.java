package neofontrender.addons.mixin.compat;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.TexturedQuad;
import neofontrender.addons.shadows.access.ModelBoxShadowGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelBox.class)
public interface AccessorModelBoxShadowGeometry extends ModelBoxShadowGeometry {
    @Override @Accessor("quadList") TexturedQuad[] nfrUi$shadowQuads();
}

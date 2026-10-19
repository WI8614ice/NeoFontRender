package neofontrender.addons.mixin;

import net.minecraft.util.ResourceLocation;
import neofontrender.addons.compat.YsmRenderProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.client.entity.CustomPlayerEntity", remap = false)
abstract class MixinYsmModelProbe implements YsmRenderProbe.ModelIdentity {
    @Shadow public abstract ResourceLocation getMainModel();
    @Shadow public abstract ResourceLocation getAnimation();
    @Shadow public abstract ResourceLocation getTexture();
    @Unique
    @Override public String nfrUi$probeModel() {
        return "model=" + YsmRenderProbe.clip(String.valueOf(getMainModel()))
                + ";animation=" + YsmRenderProbe.clip(String.valueOf(getAnimation()))
                + ";texture=" + YsmRenderProbe.clip(String.valueOf(getTexture()));
    }
}

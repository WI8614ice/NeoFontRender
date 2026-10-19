package neofontrender.addons.mixin;

import java.util.List;
import neofontrender.addons.compat.YsmRenderProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe existing CPU visibility decisions without calling or changing animation processing. */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone", remap = false)
abstract class MixinYsmBoneProbe {
    @Shadow public String name;
    @Shadow public List<?> childCubes;
    @Shadow public boolean isHidden;
    @Shadow public boolean areCubesHidden;
    @Shadow public boolean hideChildBonesToo;
    @Shadow public abstract float getPositionX();
    @Shadow public abstract float getPositionY();
    @Shadow public abstract float getPositionZ();
    @Shadow public abstract float getRotationX();
    @Shadow public abstract float getRotationY();
    @Shadow public abstract float getRotationZ();
    @Shadow public abstract float getScaleX();
    @Shadow public abstract float getScaleY();
    @Shadow public abstract float getScaleZ();
    @Shadow public abstract float getPivotX();
    @Shadow public abstract float getPivotY();
    @Shadow public abstract float getPivotZ();

    @Inject(method = "isHidden()Z", at = @At("RETURN"), require = 1)
    private void nfrUi$probeVisibility(CallbackInfoReturnable<Boolean> result) {
        nfrUi$observe("isHidden", result.getReturnValue());
    }
    @Inject(method = "cubesAreHidden()Z", at = @At("RETURN"), require = 1)
    private void nfrUi$probeCubes(CallbackInfoReturnable<Boolean> result) {
        nfrUi$observe("cubesAreHidden", result.getReturnValue());
    }
    @Unique
    private void nfrUi$observe(String gate, boolean result) {
        if (!YsmRenderProbe.wantsBone(name)) return;
        YsmRenderProbe.bone(name, getPositionX(), getPositionY(), getPositionZ(),
                getRotationX(), getRotationY(), getRotationZ(), getScaleX(), getScaleY(), getScaleZ(),
                getPivotX(), getPivotY(), getPivotZ(), isHidden, areCubesHidden, hideChildBonesToo,
                childCubes.size(), gate, result);
    }
}

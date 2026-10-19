package neofontrender.addons.mixin;

import neofontrender.addons.compat.YsmRenderProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.client.animation.AnimationManager", remap = false)
abstract class MixinYsmAnimationProbe {
    /** Both normal and loop helpers delegate to this existing three-argument request. */
    @Inject(method = "playAnimation(Lcom/elfmcys/yesstevemodel/geckolib3/core/event/predicate/AnimationEvent;"
            + "Ljava/lang/String;Lcom/elfmcys/yesstevemodel/geckolib3/core/builder/ILoopType;)"
            + "Lcom/elfmcys/yesstevemodel/geckolib3/core/PlayState;", at = @At("HEAD"), require = 1)
    private static void nfrUi$probeRequest(@Coerce Object event, String animation, @Coerce Object loop,
                                          CallbackInfoReturnable<Object> result) {
        YsmRenderProbe.requestedAnimation(animation);
    }
}

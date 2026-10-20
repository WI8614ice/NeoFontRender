package neofontrender.addons.mixin;

import com.elfmcys.yesstevemodel.geckolib3.core.AnimationState;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.Animation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.Map;
import neofontrender.addons.compat.YsmRenderProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Read descriptor-correct fields only after the existing animation processor returns normally. */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationController", remap = false)
abstract class MixinYsmControllerProbe {
    @Shadow protected Animation currentAnimation;
    @Shadow protected AnimationState animationState;

    @WrapMethod(method = "process(DLcom/elfmcys/yesstevemodel/geckolib3/core/event/predicate/AnimationEvent;"
            + "Ljava/util/List;Ljava/util/Map;Lcom/elfmcys/yesstevemodel/geckolib3/core/molang/MolangParser;Z)V",
            require = 1, expect = 1, allow = 1)
    private void nfrUi$observeProcessedController(double tick, @Coerce Object event, List<?> bones,
            Map<?, ?> snapshots, @Coerce Object parser, boolean crashOnMissingBone,
            Operation<Void> original) {
        original.call(tick, event, bones, snapshots, parser, crashOnMissingBone);
        if (YsmRenderProbe.observing()) {
            YsmRenderProbe.controllerReturned(this,
                    currentAnimation == null ? null : currentAnimation.animationName,
                    animationState == null ? null : animationState.name());
        }
    }
}

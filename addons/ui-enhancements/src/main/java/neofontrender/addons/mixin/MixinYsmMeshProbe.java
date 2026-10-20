package neofontrender.addons.mixin;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoCube;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoQuad;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import javax.vecmath.Vector3f;
import net.minecraft.client.renderer.BufferBuilder;
import neofontrender.addons.compat.YsmRenderProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Observe the existing default mesh methods; returning from a CPU buffer call is not GPU proof. */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.geckolib3.geo.IGeoRenderer", remap = false)
interface MixinYsmMeshProbe {
    @WrapMethod(method = "renderCubesOfBone(Lcom/elfmcys/yesstevemodel/geckolib3/geo/render/built/GeoBone;"
            + "Lnet/minecraft/client/renderer/BufferBuilder;FFFF)V", require = 1, expect = 1, allow = 1)
    default void nfrUi$observeBoneMesh(GeoBone bone, BufferBuilder buffer,
            float red, float green, float blue, float alpha, Operation<Void> original) throws Throwable {
        YsmRenderProbe.withMeshBone(bone.name, System.identityHashCode(bone),
                () -> original.call(bone, buffer, red, green, blue, alpha));
    }

    @WrapMethod(method = "renderCube(Lnet/minecraft/client/renderer/BufferBuilder;"
            + "Lcom/elfmcys/yesstevemodel/geckolib3/geo/render/built/GeoCube;FFFF)V",
            require = 1, expect = 1, allow = 1)
    default void nfrUi$observeCubeMesh(BufferBuilder buffer, GeoCube cube,
            float red, float green, float blue, float alpha, Operation<Void> original) throws Throwable {
        YsmRenderProbe.meshInvocation("cube", () -> original.call(buffer, cube, red, green, blue, alpha));
    }

    @WrapMethod(method = "createVerticesOfQuad(Lcom/elfmcys/yesstevemodel/geckolib3/geo/render/built/GeoQuad;"
            + "Ljavax/vecmath/Vector3f;Lnet/minecraft/client/renderer/BufferBuilder;FFFF)V",
            require = 1, expect = 1, allow = 1)
    default void nfrUi$observeQuadMesh(GeoQuad quad, Vector3f normal, BufferBuilder buffer,
            float red, float green, float blue, float alpha, Operation<Void> original) throws Throwable {
        YsmRenderProbe.meshInvocation("quad", () -> original.call(quad, normal, buffer, red, green, blue, alpha));
    }

    @WrapOperation(method = "createVerticesOfQuad(Lcom/elfmcys/yesstevemodel/geckolib3/geo/render/built/GeoQuad;"
            + "Ljavax/vecmath/Vector3f;Lnet/minecraft/client/renderer/BufferBuilder;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/BufferBuilder;func_181666_a(FFFF)Lnet/minecraft/client/renderer/BufferBuilder;",
                    remap = false), require = 1, expect = 1, allow = 1)
    default BufferBuilder nfrUi$observeVertexColor(BufferBuilder buffer,
            float red, float green, float blue, float alpha, Operation<BufferBuilder> original) {
        BufferBuilder result = original.call(buffer, red, green, blue, alpha);
        YsmRenderProbe.vertexColor(red, green, blue, alpha);
        return result;
    }

    @WrapOperation(method = "createVerticesOfQuad(Lcom/elfmcys/yesstevemodel/geckolib3/geo/render/built/GeoQuad;"
            + "Ljavax/vecmath/Vector3f;Lnet/minecraft/client/renderer/BufferBuilder;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/BufferBuilder;func_181675_d()V",
                    remap = false), require = 1, expect = 1, allow = 1)
    default void nfrUi$observeVertexEnd(BufferBuilder buffer, Operation<Void> original) {
        original.call(buffer);
        YsmRenderProbe.vertexEnded();
    }
}

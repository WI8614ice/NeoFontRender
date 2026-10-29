package neofontrender.addons.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import java.util.Collection;
import net.minecraft.util.BlockRenderLayer;
import neofontrender.addons.shadows.TerrainSceneFrame;
import neofontrender.addons.shadows.PlayerShadowRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact alpha-0.0.13 installed descriptors; this is an internal binding, not an Actinium public API. */
@Mixin(targets = "com.dhj.actinium.render.terrain.ActiniumWorldRenderer", remap = false)
public abstract class MixinActiniumTerrainShadowFrame {
    @WrapMethod(method = "drawChunkLayer(Lnet/minecraft/util/BlockRenderLayer;DDD)V",
            remap = false, require = 1)
    private void nfrUi$terrainOrigin(BlockRenderLayer layer, double x, double y, double z,
                                    Operation<Void> original) throws Throwable {
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            original.call(layer, x, y, z);
            return;
        }
        try (var ignored = TerrainSceneFrame.beginTerrain(x, y, z)) {
            PlayerShadowRuntime.INSTANCE.observeRendererBoundary("before terrain draw; layer=" + layer);
            try { original.call(layer, x, y, z); }
            finally { PlayerShadowRuntime.INSTANCE.observeRendererBoundary("after terrain draw; layer=" + layer); }
        }
    }

    @WrapMethod(method = "drawChunkLayersDeduplicated(Ljava/util/Collection;DDD)V",
            remap = false, require = 1)
    private void nfrUi$deduplicatedOrigin(Collection<BlockRenderLayer> layers, double x, double y, double z,
                                        Operation<Void> original) throws Throwable {
        if (layers.contains(BlockRenderLayer.TRANSLUCENT)) {
            original.call(layers, x, y, z);
            return;
        }
        try (var ignored = TerrainSceneFrame.beginTerrain(x, y, z)) {
            PlayerShadowRuntime.INSTANCE.observeRendererBoundary("before deduplicated terrain draw; layers=" + layers);
            try { original.call(layers, x, y, z); }
            finally { PlayerShadowRuntime.INSTANCE.observeRendererBoundary("after deduplicated terrain draw; layers=" + layers); }
        }
    }

    @Inject(method = "createChunkRenderMatrices()Ldhj/embeddedt/embeddium/impl/render/chunk/ChunkRenderMatrices;",
            at = @At("RETURN"), remap = false, require = 1)
    private void nfrUi$actualMatrices(CallbackInfoReturnable<ChunkRenderMatrices> cir) {
        var matrices = cir.getReturnValue();
        // Use the installed relocated Matrix4fc directly; no cast to source-tree org.joml types.
        TerrainSceneFrame.matrices(matrices.projection().get(new float[16]),
                matrices.modelView().get(new float[16]));
    }
}

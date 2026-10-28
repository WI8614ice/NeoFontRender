package neofontrender.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderBlockOverlayEvent;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** First-person block overlays sample the displayed eye and body axes; server suffocation is untouched. */
@Mixin(ItemRenderer.class)
public abstract class MixinItemRendererPhysicalOverlay {
    @Unique private long nfrUi$lastPhysicalOverlayProbe = Long.MIN_VALUE;

    @WrapOperation(method = {"renderOverlays(F)V", "func_78447_b(F)V"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;isEntityInsideOpaqueBlock()Z"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;func_70094_T()Z")},
            remap = false, require = 1, allow = 1)
    private boolean nfrUi$physicalOpaque(EntityPlayerSP player, Operation<Boolean> original, float partialTicks) {
        boolean nativeOpaque = original.call(player);
        FlightBodyPose pose = FlightApi.queryBodyPose(player, partialTicks);
        if (pose == null || pose.geometry == null) return nativeOpaque;
        BlockPos opaque = nfrUi$opaquePosition(player, pose);
        boolean physicalOpaque = !player.noClip && opaque != null;
        if (nativeOpaque != physicalOpaque && (nfrUi$lastPhysicalOverlayProbe == Long.MIN_VALUE
                || player.ticksExisted - nfrUi$lastPhysicalOverlayProbe >= 20)) {
            nfrUi$lastPhysicalOverlayProbe = player.ticksExisted;
            org.apache.logging.log4j.LogManager.getLogger("UIE physical overlay").info(
                    "uie-physical-overlay nativeOpaque={} sampledOpaque={} eye={},{},{} bodyUp={},{},{} nativeTransport={},{},{}",
                    nativeOpaque, physicalOpaque, pose.geometry.eye.x, pose.geometry.eye.y, pose.geometry.eye.z,
                    pose.attitude.up().x, pose.attitude.up().y, pose.attitude.up().z, player.posX, player.posY, player.posZ);
        }
        return physicalOpaque;
    }

    @WrapOperation(method = {"renderOverlays(F)V", "func_78447_b(F)V"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"),
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;func_180495_p(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;")},
            remap = false, require = 2, allow = 2)
    private IBlockState nfrUi$physicalOverlayBlock(WorldClient world, BlockPos originalPosition,
                                                  Operation<IBlockState> original, float partialTicks) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        FlightBodyPose pose = FlightApi.queryBodyPose(player, partialTicks);
        if (pose == null || pose.geometry == null) return original.call(world, originalPosition);
        BlockPos opaque = nfrUi$opaquePosition(player, pose);
        return original.call(world, opaque == null
                ? new BlockPos(pose.geometry.eye.x, pose.geometry.eye.y, pose.geometry.eye.z) : opaque);
    }

    @WrapOperation(method = {"renderOverlays(F)V", "func_78447_b(F)V"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/event/ForgeEventFactory;renderBlockOverlay(Lnet/minecraft/entity/player/EntityPlayer;FLnet/minecraftforge/client/event/RenderBlockOverlayEvent$OverlayType;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;)Z"), remap = false, require = 1, allow = 1)
    private boolean nfrUi$physicalOverlayEvent(EntityPlayer player, float partialTicks,
            RenderBlockOverlayEvent.OverlayType type, IBlockState state, BlockPos position, Operation<Boolean> original) {
        if (!(player instanceof EntityPlayerSP)) return original.call(player, partialTicks, type, state, position);
        FlightBodyPose pose = FlightApi.queryBodyPose((EntityPlayerSP) player, partialTicks);
        if (pose == null || pose.geometry == null) return original.call(player, partialTicks, type, state, position);
        BlockPos opaque = nfrUi$opaquePosition((EntityPlayerSP) player, pose);
        BlockPos sampled = opaque == null ? new BlockPos(pose.geometry.eye.x, pose.geometry.eye.y, pose.geometry.eye.z) : opaque;
        return original.call(player, partialTicks, type, player.world.getBlockState(sampled), sampled);
    }

    @Unique
    private static BlockPos nfrUi$opaquePosition(EntityPlayerSP player, FlightBodyPose pose) {
        for (int i = 0; i < 8; i++) {
            FlightVector offset = pose.attitude.rotate(((i & 1) - .5) * player.width * .8,
                    (((i >> 1) & 1) - .5) * .1, (((i >> 2) & 1) - .5) * player.width * .8);
            BlockPos point = new BlockPos(pose.geometry.eye.x + offset.x,
                    pose.geometry.eye.y + offset.y, pose.geometry.eye.z + offset.z);
            if (player.world.getBlockState(point).causesSuffocation()) return point;
        }
        return null;
    }
}

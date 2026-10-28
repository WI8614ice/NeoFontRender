package neofontrender.addons.shadows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

/** Restricts model callbacks to already loaded chunks, remembering missing neighbours rather than calling them air. */
final class ShadowBlockAccess implements IBlockAccess {
    private final WorldClient world;
    private final Map<Long, Chunk> dependencies = new LinkedHashMap<>();
    private boolean missing;

    ShadowBlockAccess(WorldClient world) { this.world = world; }
    boolean missingNeighbour() { return missing; }
    Map<Long, Chunk> dependencies() { return Collections.unmodifiableMap(new LinkedHashMap<>(dependencies)); }

    private Chunk chunk(BlockPos pos) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) missing = true;
        dependencies.put(net.minecraft.util.math.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), chunk);
        return chunk;
    }

    @Override public IBlockState getBlockState(BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() >= 256) return Blocks.AIR.getDefaultState();
        Chunk chunk = chunk(pos);
        return chunk == null ? Blocks.AIR.getDefaultState() : chunk.getBlockState(pos);
    }

    @Override public TileEntity getTileEntity(BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() >= 256) return null;
        Chunk chunk = chunk(pos);
        return chunk == null ? null : chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
    }

    @Override public int getCombinedLight(BlockPos pos, int lightValue) {
        // Lighting only controls vanilla AO/vertex colours. It is never used to invent a light direction.
        return light(EnumSkyBlock.SKY, pos) << 20 | Math.max(lightValue, light(EnumSkyBlock.BLOCK, pos)) << 4;
    }

    private int light(EnumSkyBlock type, BlockPos pos) {
        if (type == EnumSkyBlock.SKY && !world.provider.hasSkyLight()) return 0;
        if (pos.getY() < 0) pos = new BlockPos(pos.getX(), 0, pos.getZ());
        if (pos.getY() >= 256) return type.defaultLightValue;
        if (!getBlockState(pos).useNeighborBrightness()) return directLight(type, pos);
        int value = directLight(type, pos.up());
        for (EnumFacing facing : EnumFacing.HORIZONTALS) value = Math.max(value, directLight(type, pos.offset(facing)));
        return value;
    }

    private int directLight(EnumSkyBlock type, BlockPos pos) {
        if (pos.getY() >= 256) return type.defaultLightValue;
        Chunk chunk = chunk(pos);
        return chunk == null ? type.defaultLightValue : chunk.getLightFor(type, pos);
    }

    @Override public boolean isAirBlock(BlockPos pos) { return getBlockState(pos).getBlock().isAir(getBlockState(pos), this, pos); }
    @Override public Biome getBiome(BlockPos pos) {
        return chunk(pos) == null ? Biomes.PLAINS : world.getBiome(pos);
    }
    @Override public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return getBlockState(pos).getStrongPower(this, pos, direction);
    }
    @Override public WorldType getWorldType() { return world.getWorldType(); }
    @Override public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean defaultValue) {
        return chunk(pos) == null ? defaultValue : getBlockState(pos).isSideSolid(this, pos, side);
    }
}

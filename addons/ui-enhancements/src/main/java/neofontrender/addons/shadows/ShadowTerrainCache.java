package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.lwjgl.opengl.GL11;

/** Demand-built, event-invalidated section meshes and real block emitters. Never loads a chunk. */
public final class ShadowTerrainCache implements IWorldEventListener, IResourceManagerReloadListener {
    public static final ShadowTerrainCache INSTANCE = new ShadowTerrainCache();
    // A CPU cache capacity, not a per-movement/query budget; eviction only causes a later rebuild.
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final int MAX_SECTIONS = 128;
    static int sectionCapacity() { return MAX_SECTIONS; }
    public enum Availability { READY, CHUNK_NOT_LOADED, MISSING_MODEL_NEIGHBOUR }
    public record Key(int x, int y, int z) {
        public BlockPos origin() { return new BlockPos(x << 4, y << 4, z << 4); }
    }
    public record Emitter(BlockPos position, int emission) {
        public Emitter { position = position.toImmutable(); }
    }
    public record Section(Key key, Availability availability, List<ShadowTerrainMesh> meshes,
                          List<Emitter> emitters, List<BlockPos> dynamicModels, int translucentBlocks) {
        public Section {
            meshes = List.copyOf(meshes); emitters = List.copyOf(emitters); dynamicModels = List.copyOf(dynamicModels);
        }
    }
    private record Cached(Chunk chunk, Object storage, Map<Long, Chunk> dependencies, Section section, long bytes) {}
    private record Built(Section section, Map<Long, Chunk> dependencies) {}
    private final Map<Key, Cached> sections = new LinkedHashMap<>(32, .75F, true);
    private WorldClient world;
    private long bytes;

    private ShadowTerrainCache() {}

    public void bind(WorldClient next) {
        requireClientThread();
        if (world == next) return;
        if (world != null) world.removeEventListener(this);
        clear(); world = next;
        if (world != null) world.addEventListener(this);
    }

    public void unload(World source) {
        if (world == source) bind(null);
    }

    /** A missing/incomplete section is explicit, never returned as proved empty terrain. */
    public Section currentSection(Key key) {
        requireClientThread();
        Cached cached = sections.get(key);
        return cached != null && isCurrent(cached.section) ? cached.section : null;
    }

    public Section section(Key key) {
        requireClientThread();
        if (key.y < 0 || key.y > 15) return empty(key, Availability.READY);
        Chunk chunk = world == null ? null : world.getChunkProvider().getLoadedChunk(key.x, key.z);
        if (chunk == null) { remove(key); return empty(key, Availability.CHUNK_NOT_LOADED); }
        Object storage = chunk.getBlockStorageArray()[key.y];
        Cached previous = sections.get(key);
        if (previous != null && previous.chunk == chunk && previous.storage == storage
                && dependenciesLoaded(previous.dependencies)) return previous.section;
        remove(key);
        Built result = build(key);
        Section built = result.section;
        {
            long size = built.meshes.stream().mapToLong(ShadowTerrainMesh::bytes).sum()
                    + built.emitters.size() * 32L + built.dynamicModels.size() * 24L;
            sections.put(key, new Cached(chunk, storage, result.dependencies, built, size)); bytes += size;
            while (bytes > MAX_BYTES || sections.size() > MAX_SECTIONS) remove(sections.keySet().iterator().next());
        }
        return built;
    }

    private boolean dependenciesLoaded(Map<Long, Chunk> dependencies) {
        for (var dependency : dependencies.entrySet()) {
            long key = dependency.getKey();
            if (world.getChunkProvider().getLoadedChunk((int) key, (int) (key >> 32)) != dependency.getValue()) return false;
        }
        return true;
    }

    /** A saved READY object is not authority after eviction, invalidation, reload or world replacement. */
    public boolean isCurrent(Section section) {
        requireClientThread();
        if (world == null || world != Minecraft.getMinecraft().world) return false;
        Cached cached = sections.get(section.key());
        if (cached == null || cached.section != section || section.key().y < 0 || section.key().y > 15) return false;
        Chunk chunk = world.getChunkProvider().getLoadedChunk(section.key().x, section.key().z);
        return chunk == cached.chunk && chunk != null && chunk.getBlockStorageArray()[section.key().y] == cached.storage
                && dependenciesLoaded(cached.dependencies);
    }

    private Built build(Key key) {
        var access = new ShadowBlockAccess(world);
        var origin = key.origin();
        var emitters = new ArrayList<Emitter>();
        var dynamic = new ArrayList<BlockPos>();
        var builders = new LinkedHashMap<BlockRenderLayer, ShadowTerrainMesh.Builder>();
        for (BlockRenderLayer layer : new BlockRenderLayer[] {BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT_MIPPED,
                BlockRenderLayer.CUTOUT}) builders.put(layer, new ShadowTerrainMesh.Builder(layer));
        var buffer = new BufferBuilder(4096);
        buffer.setTranslation(-origin.getX(), -origin.getY(), -origin.getZ());
        var dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        BlockRenderLayer previousLayer = MinecraftForgeClient.getRenderLayer();
        int translucent = 0;
        BlockPos position = origin;
        try {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                position = origin.add(x, y, z);
                IBlockState state = access.getBlockState(position);
                int emission = state.getLightValue(access, position);
                if (emission > 0) emitters.add(new Emitter(position, emission));
                if (state.getBlock().isAir(state, access, position)) continue;
                var tile = state.getBlock().hasTileEntity(state) ? access.getTileEntity(position) : null;
                if (state.getRenderType() == EnumBlockRenderType.ENTITYBLOCK_ANIMATED
                        || tile != null && TileEntityRendererDispatcher.instance.getRenderer(tile) != null)
                    dynamic.add(position);
                if (state.getBlock().canRenderInLayer(state, BlockRenderLayer.TRANSLUCENT)) translucent++;
                if (state.getRenderType() != EnumBlockRenderType.MODEL) continue;
                for (var entry : builders.entrySet()) {
                    if (!state.getBlock().canRenderInLayer(state, entry.getKey())) continue;
                    ForgeHooksClient.setRenderLayer(entry.getKey());
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                    dispatcher.renderBlock(state, position, access, buffer);
                    buffer.finishDrawing();
                    entry.getValue().append(buffer, emission);
                    buffer.reset();
                }
            }
        } catch (RuntimeException | Error failure) {
            throw new IllegalStateException("Shadow terrain mesh failed: section=" + key + " block=" + position
                    + " layer=" + MinecraftForgeClient.getRenderLayer(), failure);
        } finally {
            ForgeHooksClient.setRenderLayer(previousLayer);
        }
        return new Built(new Section(key, access.missingNeighbour() ? Availability.MISSING_MODEL_NEIGHBOUR : Availability.READY,
                builders.values().stream().map(ShadowTerrainMesh.Builder::freeze).filter(mesh -> mesh.vertexCount() != 0).toList(),
                emitters, dynamic, translucent), access.dependencies());
    }

    private static Section empty(Key key, Availability availability) {
        return new Section(key, availability, List.of(), List.of(), List.of(), 0);
    }

    private void remove(Key key) {
        Cached removed = sections.remove(key);
        if (removed != null) bytes -= removed.bytes;
    }

    public void clear() { sections.clear(); bytes = 0; }
    @Override public void onResourceManagerReload(IResourceManager manager) { clear(); }

    private void invalidate(int x0, int y0, int z0, int x1, int y1, int z1) {
        // Neighbour model/face queries can cross a section boundary.
        var iterator = sections.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); Key key = entry.getKey();
            if (((long) key.x << 4) + 16 < x0 || ((long) key.x << 4) - 1 > x1
                    || ((long) key.y << 4) + 16 < y0 || ((long) key.y << 4) - 1 > y1
                    || ((long) key.z << 4) + 16 < z0 || ((long) key.z << 4) - 1 > z1) continue;
            bytes -= entry.getValue().bytes; iterator.remove();
        }
    }

    private static void requireClientThread() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("Shadow terrain cache requires the client thread");
    }

    @Override public void notifyBlockUpdate(World source, BlockPos pos, IBlockState old, IBlockState next, int flags) {
        if (source == world) invalidate(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }
    @Override public void notifyLightSet(BlockPos pos) {
        invalidate(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }
    @Override public void markBlockRangeForRenderUpdate(int x0, int y0, int z0, int x1, int y1, int z1) {
        invalidate(x0, y0, z0, x1, y1, z1);
    }
    @Override public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent sound, SoundCategory category,
            double x, double y, double z, float volume, float pitch) {}
    @Override public void playRecord(SoundEvent sound, BlockPos pos) {}
    @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z,
            double dx, double dy, double dz, int... parameters) {}
    @Override public void spawnParticle(int id, boolean ignoreRange, boolean minimise, double x, double y, double z,
            double dx, double dy, double dz, int... parameters) {}
    @Override public void onEntityAdded(Entity entity) {}
    @Override public void onEntityRemoved(Entity entity) {}
    @Override public void broadcastSound(int sound, BlockPos pos, int data) {}
    @Override public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {}
    @Override public void sendBlockBreakProgress(int breaker, BlockPos pos, int progress) {}
}

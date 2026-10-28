package neofontrender.addons.shadows;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import neofontrender.addons.api.flight.PlayerShadowMode;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Render-thread lease of the actual opaque terrain projection; never valid in another world pass. */
public final class TerrainSceneFrame {
    public enum Availability { WAITING_FOR_TERRAIN, READY, SHADER_PACK_OWNS_SHADOWS,
        NESTED_WORLD_PASS, NO_TERRAIN_MATRIX, INCONSISTENT_TERRAIN_TRANSFORM }

    private static final Deque<WorldScope> WORLDS = new ArrayDeque<>();
    private static long nextFrame;

    private TerrainSceneFrame() {}

    public static WorldScope beginWorld(float partialTicks, boolean shaderPack) {
        if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("finite partial tick required");
        var scope = new WorldScope(Math.incrementExact(nextFrame), partialTicks,
                !WORLDS.isEmpty() ? Availability.NESTED_WORLD_PASS
                        : shaderPack ? Availability.SHADER_PACK_OWNS_SHADOWS : Availability.WAITING_FOR_TERRAIN);
        nextFrame = scope.id;
        WORLDS.push(scope);
        return scope;
    }

    public static WorldScope current() { return WORLDS.peek(); }

    public static TerrainScope beginTerrain(double x, double y, double z) {
        WorldScope world = current();
        return new TerrainScope(world, x, y, z);
    }

    /** Only the bridge's opaque/cutout draw invokes this; matrices are copied before renderer reuse. */
    public static void matrices(float[] projection, float[] modelView) {
        WorldScope world = current();
        if (world == null || world.terrain == null || world.state != Availability.WAITING_FOR_TERRAIN) return;
        TerrainScope terrain = world.terrain;
        var sample = new Projection(projection, modelView, terrain.x, terrain.y, terrain.z);
        if (world.projection != null && !world.projection.sameTransform(sample)) {
            world.state = Availability.INCONSISTENT_TERRAIN_TRANSFORM;
            world.projection = null;
        } else world.projection = sample;
    }

    /** Called after all opaque and cutout terrain, before entities/particles/translucent receivers. */
    public static void opaqueComplete() {
        WorldScope world = current();
        if (world != null && world.state == Availability.WAITING_FOR_TERRAIN)
            world.state = world.projection == null ? Availability.NO_TERRAIN_MATRIX : Availability.READY;
    }

    public static final class WorldScope implements AutoCloseable {
        private final long id;
        private final float partialTicks;
        private final PlayerShadowMode shadowMode;
        private Availability state;
        private Projection projection;
        private TerrainScope terrain;
        private boolean closed;

        private WorldScope(long id, float partialTicks, Availability state) {
            this.id = id;
            this.partialTicks = partialTicks;
            this.shadowMode = PlayerShadowConfig.mode();
            this.state = state;
        }

        public long id() { requireLive(); return id; }
        public float partialTicks() { requireLive(); return partialTicks; }
        /** A live setting change takes effect on the next world pass, never halfway through one. */
        public PlayerShadowMode shadowMode() { requireLive(); return shadowMode; }
        public Availability availability() { requireLive(); return state; }
        public Projection projection() {
            requireLive();
            if (state != Availability.READY) throw new IllegalStateException("Terrain projection unavailable: " + state);
            return projection;
        }
        private void requireLive() {
            if (closed || WORLDS.peek() != this) throw new IllegalStateException("Expired or nested terrain frame");
        }
        @Override public void close() {
            requireLive();
            if (terrain != null) throw new IllegalStateException("Terrain draw scope outlived world pass");
            WORLDS.pop();
            closed = true;
            projection = null;
        }
    }

    public static final class TerrainScope implements AutoCloseable {
        private final WorldScope world;
        private final TerrainScope parent;
        private final double x, y, z;
        private boolean closed;

        private TerrainScope(WorldScope world, double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("finite terrain origin required");
            this.world = world;
            this.x = x; this.y = y; this.z = z;
            parent = world == null ? null : world.terrain;
            if (world != null) world.terrain = this;
        }
        @Override public void close() {
            if (closed || (world != null && (current() != world || world.terrain != this)))
                throw new IllegalStateException("Terrain draw scope closed out of order");
            if (world != null) world.terrain = parent;
            closed = true;
        }
    }

    /** Immutable matrix copies plus the exact camera transform passed to chunk rendering. */
    public static final class Projection {
        private final Matrix4f projection, modelView, inverse;
        public final double originX, originY, originZ;

        private Projection(float[] projection, float[] modelView, double x, double y, double z) {
            this.projection = matrix(projection);
            this.modelView = matrix(modelView);
            Matrix4f combined = new Matrix4f(this.projection).mul(this.modelView);
            if (!Float.isFinite(combined.determinant()) || combined.determinant() == 0)
                throw new IllegalArgumentException("noninvertible terrain projection");
            inverse = combined.invert();
            for (float element : inverse.get(new float[16])) if (!Float.isFinite(element))
                throw new IllegalArgumentException("nonfinite inverse terrain projection");
            originX = x; originY = y; originZ = z;
        }
        private static Matrix4f matrix(float[] elements) {
            Objects.requireNonNull(elements, "matrix");
            if (elements.length != 16) throw new IllegalArgumentException("sixteen matrix elements required");
            for (float element : elements) if (!Float.isFinite(element))
                throw new IllegalArgumentException("finite matrix required");
            return new Matrix4f().set(elements);
        }
        private boolean sameTransform(Projection other) {
            return originX == other.originX && originY == other.originY && originZ == other.originZ
                    && projection.equals(other.projection) && modelView.equals(other.modelView);
        }
        public float[] inverseProjectionView() { return inverse.get(new float[16]); }
        public float[] inverseModelView() { return new Matrix4f(modelView).invert().get(new float[16]); }
        public float[] projectionMatrix() { return projection.get(new float[16]); }
        public float[] modelViewMatrix() { return modelView.get(new float[16]); }

        /** Standard GL window-depth reconstruction; coordinates returned relative to the actual terrain origin. */
        public float[] relativePoint(float u, float v, float depth) {
            if (!Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(depth))
                throw new IllegalArgumentException("finite window sample required");
            return relativeClipPoint(2 * u - 1, 2 * v - 1, 2 * depth - 1);
        }

        public float[] relativeClipPoint(float x, float y, float z) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("finite clip sample required");
            Vector4f point = inverse.transform(new Vector4f(x, y, z, 1));
            if (!Float.isFinite(point.w) || point.w == 0)
                throw new IllegalArgumentException("point lies at projection infinity");
            return new float[] {point.x / point.w, point.y / point.w, point.z / point.w};
        }
    }
}

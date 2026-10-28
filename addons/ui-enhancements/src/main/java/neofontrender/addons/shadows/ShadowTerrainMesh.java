package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;

/** Actual baked terrain triangles, relative to an integer section origin, with atlas alpha retained. */
public final class ShadowTerrainMesh {
    public final BlockRenderLayer layer;
    private final float[] vertices;

    private ShadowTerrainMesh(BlockRenderLayer layer, float[] vertices) {
        this.layer = layer;
        this.vertices = vertices;
    }

    /** xyz, atlas uv, and the source block's emission; no collision-box geometry is generated. */
    public FloatBuffer vertices() { return FloatBuffer.wrap(vertices).asReadOnlyBuffer(); }
    public int vertexCount() { return vertices.length / 6; }
    public int bytes() { return Math.multiplyExact(vertices.length, Float.BYTES); }

    public static final class Builder {
        private final BlockRenderLayer layer;
        private float[] data = new float[1024];
        private int size;

        public Builder(BlockRenderLayer layer) { this.layer = layer; }

        public void append(BufferBuilder source, int emission) {
            if (!source.getVertexFormat().equals(DefaultVertexFormats.BLOCK))
                throw new IllegalArgumentException("Terrain capture requires the actual BLOCK vertex format");
            append(source.getByteBuffer(), source.getVertexCount(), source.getVertexFormat().getSize(),
                    source.getVertexFormat().getUvOffsetById(0), emission);
        }

        /** Consumes the completed quad buffer immediately; later buffer reuse cannot change this mesh. */
        void append(ByteBuffer source, int count, int stride, int uvOffset, int emission) {
            if (count < 0 || count % 4 != 0 || stride < 12 || uvOffset < 12 || uvOffset + 8 > stride
                    || (long) count * stride > source.limit() || emission < 0 || emission > 15)
                throw new IllegalArgumentException("Invalid completed terrain quad buffer");
            int needed = Math.addExact(size, Math.multiplyExact(count / 4, 36));
            if (needed > data.length) data = Arrays.copyOf(data, Math.max(needed, Math.multiplyExact(data.length, 2)));
            for (int quad = 0; quad < count; quad += 4) {
                for (int vertex : new int[] {0, 1, 2, 0, 2, 3}) {
                    int offset = (quad + vertex) * stride;
                    for (int coordinate = 0; coordinate < 3; coordinate++) add(source.getFloat(offset + coordinate * 4));
                    add(source.getFloat(offset + uvOffset));
                    add(source.getFloat(offset + uvOffset + 4));
                    add(emission / 15.0F);
                }
            }
        }

        private void add(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite baked terrain vertex");
            data[size++] = value;
        }

        public ShadowTerrainMesh freeze() { return new ShadowTerrainMesh(layer, Arrays.copyOf(data, size)); }
    }
}

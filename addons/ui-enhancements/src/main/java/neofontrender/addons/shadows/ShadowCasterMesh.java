package neofontrender.addons.shadows;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.TexturedQuad;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Frozen same-frame model vertices and skin coordinates; holds no mutable model/bone reference. */
public final class ShadowCasterMesh {
    private ShadowCasterMesh() {}

    public record Material(int texture, boolean textured, boolean alphaTest, int alphaFunction,
                           float alphaReference) {}

    /** Six floats per vertex: terrain-relative xyz and homogeneous texture stq. */
    public static final class Part {
        private final float[] vertices;
        public final Material material;
        public final float minX, minY, minZ, maxX, maxY, maxZ;

        private Part(float[] vertices, Material material) {
            this.vertices = vertices;
            this.material = material;
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
            float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
            for (int i = 0; i < vertices.length; i += 6) {
                x0 = Math.min(x0, vertices[i]); x1 = Math.max(x1, vertices[i]);
                y0 = Math.min(y0, vertices[i + 1]); y1 = Math.max(y1, vertices[i + 1]);
                z0 = Math.min(z0, vertices[i + 2]); z1 = Math.max(z1, vertices[i + 2]);
            }
            minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
        }
        public int vertexCount() { return vertices.length / 6; }
        public FloatBuffer vertices() { return FloatBuffer.wrap(vertices).asReadOnlyBuffer(); }
    }

    /** Reads the exact inflated/mirrored quads, not ModelBox's uninflated nominal bounds. */
    public static Part freeze(List<TexturedQuad[]> boxes, float scale, float[] modelToTerrain,
                              float[] textureMatrix, Material material) {
        if (!Float.isFinite(scale)) throw new IllegalArgumentException("finite model scale required");
        Matrix4f transform = new Matrix4f().set(modelToTerrain);
        Matrix4f uv = new Matrix4f().set(textureMatrix);
        List<float[]> triangles = new ArrayList<>();
        Vector4f position = new Vector4f(), texture = new Vector4f();
        for (TexturedQuad[] box : boxes) for (TexturedQuad quad : box) {
            if (quad.nVertices != 4 || quad.vertexPositions.length != 4)
                throw new IllegalArgumentException("standard four-vertex model quad required");
            float[][] points = new float[4][6];
            for (int i = 0; i < 4; i++) {
                var vertex = quad.vertexPositions[i];
                transform.transform(position.set((float) (vertex.vector3D.x * scale),
                        (float) (vertex.vector3D.y * scale), (float) (vertex.vector3D.z * scale), 1));
                uv.transform(texture.set(vertex.texturePositionX, vertex.texturePositionY, 0, 1));
                points[i] = new float[] {position.x, position.y, position.z, texture.x, texture.y, texture.w};
                for (float value : points[i]) if (!Float.isFinite(value))
                    throw new IllegalArgumentException("nonfinite frozen model vertex");
                if (position.w != 1) throw new IllegalArgumentException("affine model transform required");
            }
            // Same quad winding; shadow maps draw both sides and do not invent solid cuboids.
            for (int index : new int[] {0, 1, 2, 0, 2, 3}) triangles.add(points[index]);
        }
        if (triangles.isEmpty()) return null;
        float[] frozen = new float[Math.multiplyExact(triangles.size(), 6)];
        for (int i = 0; i < triangles.size(); i++) System.arraycopy(triangles.get(i), 0, frozen, i * 6, 6);
        return new Part(frozen, material);
    }
}

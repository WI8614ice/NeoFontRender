package neofontrender.addons.shadows;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
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

    /** Seven floats per vertex: terrain-relative xyz, homogeneous texture stq and primary alpha. */
    public static final class Part {
        private final float[] vertices;
        public final Material material;
        public final float minX, minY, minZ, maxX, maxY, maxZ;

        private Part(float[] vertices, Material material) {
            this.vertices = vertices;
            this.material = material;
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
            float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
            for (int i = 0; i < vertices.length; i += 7) {
                x0 = Math.min(x0, vertices[i]); x1 = Math.max(x1, vertices[i]);
                y0 = Math.min(y0, vertices[i + 1]); y1 = Math.max(y1, vertices[i + 1]);
                z0 = Math.min(z0, vertices[i + 2]); z1 = Math.max(z1, vertices[i + 2]);
            }
            minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
        }
        public int vertexCount() { return vertices.length / 7; }
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
            float[][] points = new float[4][7];
            for (int i = 0; i < 4; i++) {
                var vertex = quad.vertexPositions[i];
                transform.transform(position.set((float) (vertex.vector3D.x * scale),
                        (float) (vertex.vector3D.y * scale), (float) (vertex.vector3D.z * scale), 1));
                uv.transform(texture.set(vertex.texturePositionX, vertex.texturePositionY, 0, 1));
                points[i] = new float[] {position.x, position.y, position.z, texture.x, texture.y, texture.w, 1};
                for (float value : points[i]) if (!Float.isFinite(value))
                    throw new IllegalArgumentException("nonfinite frozen model vertex");
                if (position.w != 1) throw new IllegalArgumentException("affine model transform required");
            }
            // Same quad winding; shadow maps draw both sides and do not invent solid cuboids.
            for (int index : new int[] {0, 1, 2, 0, 2, 3}) triangles.add(points[index]);
        }
        if (triangles.isEmpty()) return null;
        float[] frozen = new float[Math.multiplyExact(triangles.size(), 7)];
        for (int i = 0; i < triangles.size(); i++) System.arraycopy(triangles.get(i), 0, frozen, i * 7, 7);
        return new Part(frozen, material);
    }
    /** Freezes the bytes actually submitted after CPU skinning; never keeps the reusable builder buffer. */
    public static Part freezeSubmitted(ByteBuffer source, int count, int primitive, VertexFormat format,
                                       float[] modelToTerrain, float[] textureMatrix, Material material,
                                       float currentAlpha) {
        if (primitive != 7 && primitive != 4)
            throw new UnsupportedOperationException("Shadow capture needs submitted quads or triangles: " + primitive);
        int group = primitive == 7 ? 4 : 3;
        if (count < 0 || count % group != 0) throw new IllegalArgumentException("Incomplete submitted primitive");
        int positionOffset = -1, textureOffset = -1, colorOffset = -1;
        boolean floatColor = false;
        for (int i = 0; i < format.getElementCount(); i++) {
            var element = format.getElement(i);
            switch (element.getUsage()) {
                case POSITION -> {
                    if (positionOffset >= 0 || element.getType() != VertexFormatElement.EnumType.FLOAT
                            || element.getElementCount() != 3)
                        throw new UnsupportedOperationException("Unsupported submitted position format");
                    positionOffset = format.getOffset(i);
                }
                case UV -> {
                    if (element.getIndex() != 0) continue; // Lightmap does not change the skin silhouette.
                    if (textureOffset >= 0 || element.getType() != VertexFormatElement.EnumType.FLOAT
                            || element.getElementCount() != 2)
                        throw new UnsupportedOperationException("Unsupported submitted skin UV format");
                    textureOffset = format.getOffset(i);
                }
                case COLOR -> {
                    if (colorOffset >= 0 || element.getElementCount() != 4
                            || (element.getType() != VertexFormatElement.EnumType.FLOAT
                            && element.getType() != VertexFormatElement.EnumType.UBYTE))
                        throw new UnsupportedOperationException("Unsupported submitted primary color format");
                    colorOffset = format.getOffset(i);
                    floatColor = element.getType() == VertexFormatElement.EnumType.FLOAT;
                }
                case NORMAL, PADDING -> { }
                default -> throw new UnsupportedOperationException("Unknown submitted vertex attribute: " + element.getUsage());
            }
        }
        if (positionOffset < 0 || material.textured() && textureOffset < 0)
            throw new UnsupportedOperationException("Submitted position/skin UV missing");
        int stride = format.getSize();
        int bytes = Math.multiplyExact(count, stride);
        if (stride <= 0 || bytes > source.capacity()) throw new IllegalArgumentException("Submitted vertex buffer is truncated");
        if (count == 0) return null;
        ByteBuffer data = source.asReadOnlyBuffer().order(source.order());
        data.clear(); data.limit(bytes);
        Matrix4f transform = new Matrix4f().set(modelToTerrain), uv = new Matrix4f().set(textureMatrix);
        Vector4f p = new Vector4f(), t = new Vector4f();
        float[] frozen = new float[Math.multiplyExact(count / group, group == 4 ? 42 : 21)];
        int output = 0;
        int[] order = group == 4 ? new int[] {0, 1, 2, 0, 2, 3} : new int[] {0, 1, 2};
        for (int start = 0; start < count; start += group) for (int index : order) {
            int offset = (start + index) * stride;
            transform.transform(p.set(data.getFloat(offset + positionOffset), data.getFloat(offset + positionOffset + 4),
                    data.getFloat(offset + positionOffset + 8), 1));
            uv.transform(t.set(textureOffset < 0 ? 0 : data.getFloat(offset + textureOffset),
                    textureOffset < 0 ? 0 : data.getFloat(offset + textureOffset + 4), 0, 1));
            float alpha = colorOffset < 0 ? currentAlpha : floatColor ? data.getFloat(offset + colorOffset + 12)
                    : Byte.toUnsignedInt(data.get(offset + colorOffset + 3)) / 255.0F;
            if (p.w != 1) throw new IllegalArgumentException("Affine submitted model transform required");
            int first = output;
            frozen[output++] = p.x; frozen[output++] = p.y; frozen[output++] = p.z;
            frozen[output++] = t.x; frozen[output++] = t.y; frozen[output++] = t.w; frozen[output++] = alpha;
            for (int i = first; i < output; i++) if (!Float.isFinite(frozen[i]))
                throw new IllegalArgumentException("Nonfinite submitted model vertex");
        }
        return new Part(frozen, material);
    }

}

package neofontrender.addons.api.flight;

import java.nio.ReadOnlyBufferException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import java.util.List;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import neofontrender.addons.shadows.ShadowCasterMesh;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Tests the actual immutable geometry builder; no model animation or GL calls. */
class ShadowCasterMeshTest {
    private static TexturedQuad quad() {
        return new TexturedQuad(new PositionTextureVertex[] {
                new PositionTextureVertex(-.25F, 0, 0, 0, 0),
                new PositionTextureVertex(1.25F, 0, 0, 1, 0),
                new PositionTextureVertex(1.25F, 1, 0, 1, 1),
                new PositionTextureVertex(-.25F, 1, 0, 0, 1) });
    }
    private static final ShadowCasterMesh.Material SKIN = new ShadowCasterMesh.Material(3, true, true, 516, .1F);

    @Test void frozenOuterVerticesSurviveBoneAndTextureMutation() {
        TexturedQuad quad = quad();
        float[] transform = new Matrix4f().translate(10, 20, 30).get(new float[16]);
        float[] uv = new Matrix4f().translate(.25F, .5F, 0).get(new float[16]);
        var part = ShadowCasterMesh.freeze(List.<TexturedQuad[]>of(new TexturedQuad[] {quad}), 2, transform, uv, SKIN);
        assertNotNull(part);
        assertEquals(6, part.vertexCount());
        assertEquals(9.5F, part.minX);
        assertEquals(12.5F, part.maxX, "bounds must contain actual outer vertices, not a nominal cuboid");
        assertEquals(20, part.minY); assertEquals(22, part.maxY);
        assertSame(SKIN, part.material);
        quad.vertexPositions[0] = new PositionTextureVertex(999, 999, 999, 9, 9);
        transform[12] = 900;
        uv[12] = 900;
        var frozen = part.vertices();
        assertEquals(9.5F, frozen.get(0));
        assertEquals(.25F, frozen.get(3)); assertEquals(.5F, frozen.get(4));
        assertThrows(ReadOnlyBufferException.class, () -> frozen.put(0, 800));
        frozen.position(6);
        assertEquals(0, part.vertices().position(), "each caller has an independent read cursor");
    }

    @Test void textureProjectionQIsPreservedInsteadOfBakingAnIncorrectAffineUv() {
        var uv = new Matrix4f().m03(.5F);
        var part = ShadowCasterMesh.freeze(List.<TexturedQuad[]>of(new TexturedQuad[] {quad()}), 1,
                new Matrix4f().get(new float[16]), uv.get(new float[16]), SKIN);
        assertEquals(1.5F, part.vertices().get(12)); // vertex 1 has u=1, q=1+.5*u
    }

    @Test void anEmptyMeshDoesNotInventAProxyShape() {
        assertNull(ShadowCasterMesh.freeze(List.of(), 1, new Matrix4f().get(new float[16]),
                new Matrix4f().get(new float[16]), SKIN));
    }
    @Test void submittedCpuSkinnedVerticesRetainAlphaAndSurviveBuilderReuse() {
        var builder = new BufferBuilder(256);
        builder.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        for (int i = 0; i < 4; i++) builder.pos(i, i % 2, 2).tex(i * .25, .5)
                .color(1F, 1F, 1F, i == 1 ? .5F : i == 2 ? 0F : 1F).normal(0, 0, 1).endVertex();
        int oldPosition = builder.getByteBuffer().position(), oldLimit = builder.getByteBuffer().limit();
        var part = ShadowCasterMesh.freezeSubmitted(builder.getByteBuffer(), builder.getVertexCount(),
                builder.getDrawMode(), builder.getVertexFormat(),
                new Matrix4f().translate(10, 20, 30).get(new float[16]),
                new Matrix4f().get(new float[16]), SKIN, .1F);
        assertNotNull(part); assertEquals(6, part.vertexCount());
        assertEquals(10F, part.vertices().get(0)); assertEquals(32F, part.vertices().get(2));
        assertEquals(1F, part.vertices().get(6), "primary vertex color replaces current GL color");
        assertEquals(127F / 255F, part.vertices().get(13));
        assertEquals(0F, part.vertices().get(20));
        assertEquals(oldPosition, builder.getByteBuffer().position());
        assertEquals(oldLimit, builder.getByteBuffer().limit());
        builder.getByteBuffer().putFloat(0, 999F); builder.reset();
        assertEquals(10F, part.vertices().get(0), "frozen mesh cannot alias the reusable native batch");
        assertEquals(13F, part.maxX);
    }

    @Test void uncoloredBatchUsesCurrentPrimaryAlphaAndRejectsUnsupportedPrimitives() {
        var builder = new BufferBuilder(128);
        builder.begin(4, DefaultVertexFormats.POSITION_TEX);
        for (int i = 0; i < 3; i++) builder.pos(i, 0, 0).tex(i, 0).endVertex();
        var identity = new Matrix4f().get(new float[16]);
        var part = ShadowCasterMesh.freezeSubmitted(builder.getByteBuffer(), 3, 4,
                builder.getVertexFormat(), identity, identity, SKIN, .25F);
        assertNotNull(part); assertEquals(3, part.vertexCount()); assertEquals(.25F, part.vertices().get(6));
        assertThrows(UnsupportedOperationException.class, () -> ShadowCasterMesh.freezeSubmitted(
                builder.getByteBuffer(), 3, 1, builder.getVertexFormat(), identity, identity, SKIN, 1));
        assertThrows(IllegalArgumentException.class, () -> ShadowCasterMesh.freezeSubmitted(
                builder.getByteBuffer(), 3, 7, builder.getVertexFormat(), identity, identity, SKIN, 1));
    }

    @Test void submittedFloatColorUsesDeclaredByteOrder() {
        var format = new VertexFormat()
                .addElement(new VertexFormatElement(0, VertexFormatElement.EnumType.FLOAT,
                        VertexFormatElement.EnumUsage.POSITION, 3))
                .addElement(new VertexFormatElement(0, VertexFormatElement.EnumType.FLOAT,
                        VertexFormatElement.EnumUsage.UV, 2))
                .addElement(new VertexFormatElement(0, VertexFormatElement.EnumType.FLOAT,
                        VertexFormatElement.EnumUsage.COLOR, 4));
        var identity = new Matrix4f().get(new float[16]);
        for (var order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            var bytes = ByteBuffer.allocate(format.getSize() * 3).order(order);
            for (int i = 0; i < 3; i++) bytes.putFloat(i + .125F).putFloat(i).putFloat(2)
                    .putFloat(.25F).putFloat(.5F).putFloat(1).putFloat(1).putFloat(1).putFloat((i + 1) * .2F);
            var part = ShadowCasterMesh.freezeSubmitted(bytes, 3, 4, format, identity, identity, SKIN, 1);
            assertNotNull(part); assertEquals(.125F, part.minX);
            assertEquals(.2F, part.vertices().get(6)); assertEquals(.4F, part.vertices().get(13));
            assertEquals(.6F, part.vertices().get(20));
        }
    }

}

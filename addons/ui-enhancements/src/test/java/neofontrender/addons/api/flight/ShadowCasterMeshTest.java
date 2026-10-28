package neofontrender.addons.api.flight;

import java.nio.ReadOnlyBufferException;
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
        assertEquals(1.5F, part.vertices().get(11)); // vertex 1 has u=1, q=1+.5*u
    }

    @Test void anEmptyMeshDoesNotInventAProxyShape() {
        assertNull(ShadowCasterMesh.freeze(List.of(), 1, new Matrix4f().get(new float[16]),
                new Matrix4f().get(new float[16]), SKIN));
    }
}

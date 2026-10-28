package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: freeze the actual BLOCK quad layout; no game, GL context or mock world. */
class ShadowTerrainMeshTest {
    @Test void thinQuadKeepsActualShapeUvEmissionAndDoesNotBorrowBuffer() {
        ByteBuffer source = ByteBuffer.allocate(4 * 28).order(ByteOrder.nativeOrder());
        float[][] points = {{.4375F, 0, 0}, {.4375F, 1, 0}, {.4375F, 1, 1}, {.4375F, 0, 1}};
        for (int v = 0; v < 4; v++) {
            for (int axis = 0; axis < 3; axis++) source.putFloat(v * 28 + axis * 4, points[v][axis]);
            source.putFloat(v * 28 + 16, v / 4F); source.putFloat(v * 28 + 20, .75F);
        }
        var builder = new ShadowTerrainMesh.Builder(BlockRenderLayer.CUTOUT);
        builder.append(source, 4, 28, 16, 12);
        var mesh = builder.freeze();
        source.putFloat(0, 99);
        var vertices = mesh.vertices();
        assertEquals(6, mesh.vertexCount());
        assertEquals(6 * 6 * 4, mesh.bytes());
        assertTrue(vertices.isReadOnly());
        int[] winding = {0, 1, 2, 0, 2, 3};
        for (int i = 0; i < 6; i++) {
            assertEquals(.4375F, vertices.get(i * 6));
            assertEquals(points[winding[i]][1], vertices.get(i * 6 + 1));
            assertEquals(points[winding[i]][2], vertices.get(i * 6 + 2));
            assertEquals(winding[i] / 4F, vertices.get(i * 6 + 3));
            assertEquals(.75F, vertices.get(i * 6 + 4));
            assertEquals(.8F, vertices.get(i * 6 + 5));
        }
    }

    @Test void malformedQuadsFailInsteadOfBecomingSolidCubes() {
        var builder = new ShadowTerrainMesh.Builder(BlockRenderLayer.SOLID);
        assertThrows(IllegalArgumentException.class, () -> builder.append(ByteBuffer.allocate(112), 3, 28, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.append(ByteBuffer.allocate(112), 4, 28, 24, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.append(ByteBuffer.allocate(112), 4, 28, 16, 16));
        assertEquals(0, builder.freeze().vertexCount());
    }
}

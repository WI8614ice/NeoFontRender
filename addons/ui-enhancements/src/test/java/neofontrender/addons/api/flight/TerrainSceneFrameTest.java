package neofontrender.addons.api.flight;

import neofontrender.addons.shadows.TerrainSceneFrame;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Exercises the actual frame lease and reconstruction without creating a GL context. */
class TerrainSceneFrameTest {
    @Test void terrainOriginComesFromDrawArgumentsAndMatrixCopiesDoNotFollowRendererMutation() {
        float[] projection = new Matrix4f().get(new float[16]);
        float[] view = new Matrix4f().translate(0, 1.62F, 0).get(new float[16]);
        try (var frame = TerrainSceneFrame.beginWorld(.5F, false)) {
            assertThrows(IllegalStateException.class, frame::projection);
            try (var ignored = TerrainSceneFrame.beginTerrain(100, 71.62, -20)) {
                TerrainSceneFrame.matrices(projection, view);
            }
            view[13] = 900;
            TerrainSceneFrame.opaqueComplete();
            assertEquals(TerrainSceneFrame.Availability.READY, frame.availability());
            var sampled = frame.projection();
            assertEquals(100, sampled.originX);
            assertEquals(71.62, sampled.originY);
            assertEquals(-20, sampled.originZ);
            assertArrayEquals(new float[] {0, -1.62F, 0}, sampled.relativePoint(.5F, .5F, .5F), 1e-6F);
        }
        assertNull(TerrainSceneFrame.current());
    }

    @Test void nestedPassPackOwnershipAndAbsentTerrainCannotBorrowAnOuterProjection() {
        try (var outer = TerrainSceneFrame.beginWorld(0, false)) {
            try (var nested = TerrainSceneFrame.beginWorld(0, false)) {
                assertEquals(TerrainSceneFrame.Availability.NESTED_WORLD_PASS, nested.availability());
                assertThrows(IllegalStateException.class, outer::projection);
                TerrainSceneFrame.opaqueComplete();
                assertThrows(IllegalStateException.class, nested::projection);
            }
            TerrainSceneFrame.opaqueComplete();
            assertEquals(TerrainSceneFrame.Availability.NO_TERRAIN_MATRIX, outer.availability());
        }
        try (var pack = TerrainSceneFrame.beginWorld(0, true)) {
            TerrainSceneFrame.opaqueComplete();
            assertEquals(TerrainSceneFrame.Availability.SHADER_PACK_OWNS_SHADOWS, pack.availability());
            assertThrows(IllegalStateException.class, pack::projection);
        }
    }

    @Test void aChangedTerrainTransformInvalidatesTheFrameInsteadOfGuessing() {
        float[] identity = new Matrix4f().get(new float[16]);
        try (var frame = TerrainSceneFrame.beginWorld(.25F, false)) {
            try (var ignored = TerrainSceneFrame.beginTerrain(1, 2, 3)) {
                TerrainSceneFrame.matrices(identity, identity);
            }
            try (var ignored = TerrainSceneFrame.beginTerrain(1, 3, 3)) {
                TerrainSceneFrame.matrices(identity, identity);
            }
            TerrainSceneFrame.opaqueComplete();
            assertEquals(TerrainSceneFrame.Availability.INCONSISTENT_TERRAIN_TRANSFORM, frame.availability());
            assertThrows(IllegalStateException.class, frame::projection);
        }
    }
}

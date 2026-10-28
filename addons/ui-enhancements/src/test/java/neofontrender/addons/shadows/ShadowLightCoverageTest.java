package neofontrender.addons.shadows;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: production coverage math only, no GL queries or world loading. */
class ShadowLightCoverageTest {
    @Test void verticalSkyPathRequiresEveryRealSectionUpToWorldTop() {
        try (var frame = readyFrame()) {
            var view = ShadowLightView.directional(8, 64, 8, 2, 0, 1, 0, 195);
            var coverage = ShadowLightCoverage.required(view, frame.projection());
            assertNull(coverage.unavailable());
            assertEquals(13, coverage.keys().size());
            assertTrue(coverage.keys().contains(new ShadowTerrainCache.Key(0, 3, 0)));
            assertTrue(coverage.keys().contains(new ShadowTerrainCache.Key(0, 15, 0)));
            assertTrue(coverage.keys().stream().allMatch(k -> k.x() == 0 && k.z() == 0));
        }
    }

    @Test void diagonalLightDoesNotRequireTheWholeFrustumBoundingBox() {
        try (var frame = readyFrame()) {
            float diagonal = (float) Math.sqrt(.5);
            var view = ShadowLightView.directional(8, 64, 8, 2, diagonal, diagonal, 0, 300);
            var coverage = ShadowLightCoverage.required(view, frame.projection());
            assertNull(coverage.unavailable());
            assertTrue(coverage.keys().size() < 100);
            assertTrue(coverage.keys().contains(new ShadowTerrainCache.Key(0, 4, 0)));
            assertTrue(coverage.keys().stream().anyMatch(k -> k.y() == 15));
        }
    }

    private static TerrainSceneFrame.WorldScope readyFrame() {
        var frame = TerrainSceneFrame.beginWorld(0, false);
        float[] identity = new Matrix4f().get(new float[16]);
        try (var ignored = TerrainSceneFrame.beginTerrain(0, 0, 0)) {
            TerrainSceneFrame.matrices(identity, identity);
        }
        TerrainSceneFrame.opaqueComplete();
        return frame;
    }
}

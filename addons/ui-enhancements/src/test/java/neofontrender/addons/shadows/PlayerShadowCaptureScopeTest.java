package neofontrender.addons.shadows;

import java.util.UUID;
import neofontrender.addons.compat.YsmRenderPurpose;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Calls the production frame/identity lease without constructing a player or a GL context. */
class PlayerShadowCaptureScopeTest {
    @Test void nestedNonPlayerModelCannotTouchOrInvalidateTheOuterCollector() throws Throwable {
        try (var outer = readyFrame()) {
            try {
                YsmRenderPurpose.withWorldRender(() -> PlayerShadowCapture.withCapture(outer, new Object(),
                        new UUID(0, 1), null, () -> {
                            try (var nested = TerrainSceneFrame.beginWorld(0, false)) {
                                // A null model is intentional: this foreign frame must be rejected before
                                // reading any model field or GL state, even without a nested player fence.
                                PlayerShadowCapture.modelPart(null, Float.NaN, Float.NaN, false);
                                PlayerShadowCapture.modelFailed();
                                assertEquals(TerrainSceneFrame.Availability.NESTED_WORLD_PASS, nested.availability());
                            }
                        }));
                var result = PlayerShadowCapture.currentCasters();
                assertEquals(1, result.size());
                assertEquals(PlayerShadowCapture.Availability.NO_GEOMETRY, result.getFirst().availability());
                assertTrue(result.getFirst().parts().isEmpty());
                assertTrue(PlayerShadowCapture.hasCurrentCaster(new UUID(0, 1)), "No geometry is still an attempted body, not permission to animate again");
            } finally { PlayerShadowCapture.endWorld(outer); }
        }
        assertTrue(PlayerShadowCapture.currentCasters().isEmpty());
        assertFalse(PlayerShadowCapture.hasCurrentCaster(new UUID(0, 1)));
    }

    @Test void aFailureCaughtInsideTheRendererCannotPublishAHealthyCaster() throws Throwable {
        try (var frame = readyFrame()) {
            try {
                var id = new UUID(0, 4);
                PlayerShadowCapture.withCapture(frame, new Object(), id, null, PlayerShadowCapture::modelFailed);
                var caster = PlayerShadowCapture.currentCasters().getFirst();
                assertEquals(PlayerShadowCapture.Availability.MODEL_RENDER_FAILED, caster.availability());
                assertTrue(caster.parts().isEmpty());
                assertTrue(PlayerShadowCapture.hasCurrentCaster(id), "Failed body is not re-rendered by the first-person proxy");
            } finally { PlayerShadowCapture.endWorld(frame); }
        }
    }

    @Test void failedRenderReleasesItsLeaseAndDoesNotPublishAPartialCaster() throws Throwable {
        try (var frame = readyFrame()) {
            try {
                var cause = new IllegalStateException("original-render-failure");
                assertSame(cause, assertThrows(IllegalStateException.class,
                        () -> PlayerShadowCapture.withCapture(frame, new Object(), new UUID(0, 2), null,
                                () -> { throw cause; })));
                assertTrue(PlayerShadowCapture.currentCasters().isEmpty());
                PlayerShadowCapture.withCapture(frame, new Object(), new UUID(0, 3), null, () -> {});
                assertEquals(new UUID(0, 3), PlayerShadowCapture.currentCasters().getFirst().playerId());
            } finally { PlayerShadowCapture.endWorld(frame); }
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

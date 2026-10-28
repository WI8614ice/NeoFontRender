package neofontrender.addons.shadows;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: actual light-view mathematics with independent physical distance/depth expectations; no GL. */
class ShadowLightViewTest {
    private static Vector4f project(ShadowLightView view, float x, float y, float z) {
        return new Matrix4f().set(view.matrix()).transform(new Vector4f(x, y, z, 1));
    }

    @Test void sixAxesPlaceCenterAtKnownDistanceAndSourceSideBeforeReceiver() {
        for (float[] direction : new float[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            var view = ShadowLightView.directional(0, 0, 0, 2, direction[0], direction[1], direction[2], 10);
            var center = project(view, 0, 0, 0);
            assertEquals(0, center.x, 1E-6); assertEquals(0, center.y, 1E-6); assertEquals(1, center.w, 1E-6);
            assertEquals((10 - .01) / (12 - .01), center.z * .5 + .5, 1E-6);
            var towardsSource = project(view, direction[0], direction[1], direction[2]);
            assertTrue(towardsSource.z < center.z);
            var awayFromSource = project(view, -direction[0], -direction[1], -direction[2]);
            assertTrue(awayFromSource.z > center.z);
        }
    }

    @Test void actualPointSourceHasPerspectiveDepthAndRejectsAnEnclosedSource() {
        var view = ShadowLightView.point(0, 0, 5, 0, 0, 0, 1, 6);
        var center = project(view, 0, 0, 0);
        assertEquals(0, center.x, 1E-6); assertEquals(0, center.y, 1E-6);
        assertEquals(6 * (1 - .01 / 5) / (6 - .01), (center.z / center.w) * .5 + .5, 1E-6);
        assertThrows(IllegalArgumentException.class, () -> ShadowLightView.point(0, 0, .5F, 0, 0, 0, 1, 6));
        assertThrows(IllegalArgumentException.class, () -> ShadowLightView.directional(0, 0, 0, 2, 0, 2, 0, 10));
    }
}

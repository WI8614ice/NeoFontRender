package neofontrender.addons.shadows;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Actual depth-reconstruction convention, independent of GL context creation. */
class WindowDepthMappingTest {
    @Test void reversedWindowRangeKeepsNearAndFarInTheirActualClipCoordinates() {
        var mapping = new WindowDepthMapping(1, 0, 0, false, false);
        assertEquals(-1, mapping.toClip(.5F, .5F, 1).z());
        assertEquals(1, mapping.toClip(.5F, .5F, 0).z());
        assertEquals(0, mapping.toClip(.5F, .5F, .5F).z());
    }

    @Test void clipControlAndNondefaultWindowRangeAreNotTreatedAsConventionalDepth() {
        var mapping = new WindowDepthMapping(.2, .8, 1, true, true);
        assertEquals(0, mapping.toClip(.5F, 0, .2F).z(), 1e-6F);
        assertEquals(.5, mapping.toClip(.5F, .5F, .5F).z(), 1e-6F);
        assertEquals(1, mapping.toClip(.5F, 1, .8F).z(), 1e-6F);
        assertEquals(1, mapping.toClip(.5F, 0, .2F).y());
        assertEquals(-1, mapping.toClip(.5F, 1, .8F).y());
    }

    @Test void singularOrNonfiniteDepthRangeCannotPretendToBeAValidCapture() {
        assertThrows(IllegalArgumentException.class, () -> new WindowDepthMapping(.5, .5, 1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new WindowDepthMapping(Double.NaN, 1, 1, false, false));
    }

    @Test void receiverRasterConversionChangesOnlyActualClipConventionNotWindowRange() {
        for (boolean zeroToOne : new boolean[] {false, true}) for (boolean upperLeft : new boolean[] {false, true}) {
            var mapping = new WindowDepthMapping(1, 0, 0, zeroToOne, upperLeft);
            var conversion = new Matrix4f().set(mapping.conventionalProjection(new Matrix4f().get(new float[16])));
            var p = conversion.transform(new Vector4f(.2F, .25F, zeroToOne ? .75F : .5F, 1));
            assertEquals(.2F, p.x, 1e-6F);
            assertEquals(upperLeft ? -.25F : .25F, p.y, 1e-6F);
            assertEquals(.5F, p.z, 1e-6F);
            assertEquals(1, p.w, 1e-6F);
        }
    }
}

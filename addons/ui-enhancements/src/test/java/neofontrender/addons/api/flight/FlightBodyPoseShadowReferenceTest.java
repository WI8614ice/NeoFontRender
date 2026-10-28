package neofontrender.addons.api.flight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source regression cases only; NOT_RUN under user-owned runtime validation. */
class FlightBodyPoseShadowReferenceTest {
    private static FlightBodyPose upright() {
        return FlightBodyPose.modelUpY(new FlightAttitude(0, 0, 0, 1));
    }

    @Test void aCardinalLookingFreePoseRemainsFreeAcrossGeometryUpdates() {
        FlightBodyPose free = upright().withFreeShadowReference();
        FlightBodyGeometry geometry = new FlightBodyGeometry(new FlightVector(1, 2, 3),
                new FlightVector(1, 3, 3), .3, new FlightVector(1, 3.4, 3));
        FlightBodyPose sampled = free.withGeometry(geometry);
        assertEquals(FlightBodyPose.ShadowReference.FREE, sampled.shadowReference);
        assertNull(sampled.shadowAxisUp);
        assertSame(geometry, sampled.geometry);
        assertSame(geometry, upright().withGeometry(geometry).withFreeShadowReference().geometry);
        assertSame(geometry, upright().withGeometry(geometry)
                .withAxialShadowReference(new FlightVector(0, 1, 0)).geometry);
        assertSame(geometry, upright().withAxialShadowReference(new FlightVector(0, -1, 0))
                .withGeometry(geometry).geometry);
        assertEquals(FlightBodyPose.ShadowReference.UNSPECIFIED, upright().shadowReference);
    }

    @Test void theAcceptedAxisSurvivesAnIndependentVisualAttitude() {
        FlightBodyPose pose = upright().withAxialShadowReference(new FlightVector(-1, 0, 0));
        assertEquals(FlightBodyPose.ShadowReference.AXIAL, pose.shadowReference);
        assertEquals(-1, pose.shadowAxisUp.x);
        assertEquals(0, pose.shadowAxisUp.y);
        assertEquals(0, pose.shadowAxisUp.z);
        assertEquals(1, pose.attitude.w);
        FlightBodyPose free = pose.withFreeShadowReference();
        assertEquals(FlightBodyPose.ShadowReference.FREE, free.shadowReference);
        assertNull(free.shadowAxisUp);
        assertEquals(FlightBodyPose.ShadowReference.AXIAL, pose.shadowReference);
    }

    @Test void aDiagonalOrNonFiniteVectorCannotClaimAnAxialReference() {
        for (FlightVector invalid : new FlightVector[] { new FlightVector(0, 0, 0),
                new FlightVector(1, 1, 0), new FlightVector(0, .5, 0),
                new FlightVector(Double.NaN, 0, 0), new FlightVector(0, 0, Double.POSITIVE_INFINITY) }) {
            assertThrows(IllegalArgumentException.class, () -> upright().withAxialShadowReference(invalid));
        }
    }
}

package neofontrender.addons.flight;

import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyGeometry;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source regression for the visual capsule contract; not a collision/physics test. */
class FlightBodyGeometryTest {
    @Test void signedDistanceFollowsHorizontalBodyAndEndCaps() {
        FlightBodyGeometry geometry = new FlightBodyGeometry(new FlightVector(-1, 2, 3),
                new FlightVector(1, 2, 3), .3, new FlightVector(.8, 2, 3));
        assertEquals(-.3, geometry.signedDistance(new FlightVector(0, 2, 3)), 1e-12);
        assertEquals(.1, geometry.signedDistance(new FlightVector(0, 2.4, 3)), 1e-12);
        assertEquals(0, geometry.signedDistance(new FlightVector(1.3, 2, 3)), 1e-12);
        assertEquals(.2, geometry.signedDistance(new FlightVector(-1.5, 2, 3)), 1e-12);
        FlightBodyGeometry sphere = new FlightBodyGeometry(new FlightVector(0, 0, 0),
                new FlightVector(0, 0, 0), .3, new FlightVector(0, 0, 0));
        assertEquals(.2, sphere.signedDistance(new FlightVector(0, .5, 0)), 1e-12);
    }

    @Test void geometryExtensionPreservesLegacyPoseAndAllRootHeadFields() {
        FlightAttitude body = FlightAttitude.fromMinecraftDegrees(10, 20, 30);
        FlightAttitude head = FlightAttitude.fromMinecraftDegrees(30, 20, 10);
        FlightVector root = new FlightVector(1, 2, 3);
        FlightBodyPose legacy = FlightBodyPose.modelUpY(body, head, root);
        assertNull(legacy.geometry);
        FlightBodyGeometry geometry = new FlightBodyGeometry(root, new FlightVector(2, 2, 3), .3, root);
        FlightBodyPose sampled = legacy.withGeometry(geometry);
        assertSame(body, sampled.attitude);
        assertSame(head, sampled.headWorldAttitude);
        assertSame(root, sampled.rootWorldOffset);
        assertSame(legacy.basis, sampled.basis);
        assertSame(geometry, sampled.geometry);
        assertNull(legacy.geometry);
    }
}

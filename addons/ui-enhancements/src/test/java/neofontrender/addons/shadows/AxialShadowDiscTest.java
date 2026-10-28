package neofontrender.addons.shadows;

import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightBodyBounds;
import neofontrender.addons.api.flight.FlightVector;
import neofontrender.addons.api.flight.PlayerShadowMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: actual geometry helper, no model/GL/game execution. */
class AxialShadowDiscTest {
    @Test void allSixAcceptedAxesUseThePhysicalBottomAndPerpendicularWidth() {
        var q = FlightAttitude.fromMinecraftDegrees(37, 23, 61); // visual interpolation must not choose support
        for (var up : new FlightVector[] {new FlightVector(1, 0, 0), new FlightVector(-1, 0, 0),
                new FlightVector(0, 1, 0), new FlightVector(0, -1, 0),
                new FlightVector(0, 0, 1), new FlightVector(0, 0, -1)}) {
            double hx = up.x == 0 ? .3 : .9, hy = up.y == 0 ? .3 : .9, hz = up.z == 0 ? .3 : .9;
            var bounds = new FlightBodyBounds(100 - hx, 50 - hy, -30 - hz, 100 + hx, 50 + hy, -30 + hz);
            var pose = FlightBodyPose.modelUpY(q).withAxialShadowReference(up).withShadowBounds(bounds);
            var disc = AxialShadowDisc.from(PlayerShadowMode.AXIAL_DISC, pose, 99, 49, -31, 2);
            assertEquals(1 - up.x * .9, disc.x(), 1e-6);
            assertEquals(1 - up.y * .9, disc.y(), 1e-6);
            assertEquals(1 - up.z * .9, disc.z(), 1e-6);
            assertEquals(.3, disc.radius(), 1e-6);
            var region = disc.receiverRegion();
            assertEquals(disc.x() - up.x, region.x(), 1e-6);
            assertEquals(disc.y() - up.y, region.y(), 1e-6);
            assertEquals(disc.z() - up.z, region.z(), 1e-6);
            assertEquals(Math.hypot(.3F, 1F), region.radius(), 1e-6);
        }
    }

    @Test void freeNeverCreatesGeometryEvenAtAnIdentityQuaternion() {
        var pose = FlightBodyPose.modelUpY(new FlightAttitude(0, 0, 0, 1));
        for (var mode : PlayerShadowMode.values()) {
            assertNull(AxialShadowDisc.from(mode, pose.withFreeShadowReference(), 0, 0, 0, 2));
            assertNull(AxialShadowDisc.from(mode, pose, 0, 0, 0, 2));
        }
        assertNull(AxialShadowDisc.from(PlayerShadowMode.REAL_PLAYER_PROJECTION,
                pose.withAxialShadowReference(new FlightVector(0, 1, 0)), 0, 0, 0, 2));
        assertNull(AxialShadowDisc.from(PlayerShadowMode.AXIAL_DISC,
                pose.withAxialShadowReference(new FlightVector(1, 0, 0)), 0, 0, 0, 2));
    }
}

package neofontrender.addons.api.flight;

import neofontrender.addons.shadows.PlayerShadowDrawPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: source cases for the actual draw policy, not a simulated render test. */
class PlayerShadowDrawPolicyTest {
    @Test void freeIdentityAndRolledFreeNeverEmitADisc() {
        for (FlightAttitude q : new FlightAttitude[] {new FlightAttitude(0, 0, 0, 1),
                FlightAttitude.fromMinecraftDegrees(30, 20, 90)}) {
            var free = FlightBodyPose.modelUpY(q).withFreeShadowReference();
            assertFalse(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.AXIAL_DISC, free));
            assertTrue(PlayerShadowDrawPolicy.suppressesVanillaDisc(PlayerShadowMode.AXIAL_DISC, free));
            assertFalse(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.REAL_PLAYER_PROJECTION, free));
            assertTrue(PlayerShadowDrawPolicy.suppressesVanillaDisc(PlayerShadowMode.REAL_PLAYER_PROJECTION, free));
        }
    }

    @Test void modeAndReferenceChangesLeaveNoPreviousDiscDecision() {
        var pose = FlightBodyPose.modelUpY(new FlightAttitude(0, 0, 0, 1));
        var axial = pose.withAxialShadowReference(new FlightVector(-1, 0, 0));
        assertFalse(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.AXIAL_DISC, pose));
        assertFalse(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.AXIAL_DISC, null));
        assertTrue(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.AXIAL_DISC, axial));
        assertFalse(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.AXIAL_DISC,
                axial.withFreeShadowReference()));
        assertFalse(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.REAL_PLAYER_PROJECTION, axial));
        assertTrue(PlayerShadowDrawPolicy.emitsAxialDisc(PlayerShadowMode.AXIAL_DISC, axial));
    }
}

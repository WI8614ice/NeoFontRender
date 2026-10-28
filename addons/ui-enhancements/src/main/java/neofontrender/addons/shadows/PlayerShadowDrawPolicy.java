package neofontrender.addons.shadows;

import java.util.Objects;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.PlayerShadowMode;

/** Decides eligibility from the owner's accepted posture, never from G or the visual quaternion. */
public final class PlayerShadowDrawPolicy {
    private PlayerShadowDrawPolicy() {}

    public static boolean emitsAxialDisc(PlayerShadowMode mode, FlightBodyPose pose) {
        Objects.requireNonNull(mode, "mode");
        return mode == PlayerShadowMode.AXIAL_DISC && pose != null
                && pose.shadowReference == FlightBodyPose.ShadowReference.AXIAL;
    }

    /** Removes the vanilla Y-down blob too; otherwise a Free player would still have a disc. */
    public static boolean suppressesVanillaDisc(PlayerShadowMode mode, FlightBodyPose pose) {
        Objects.requireNonNull(mode, "mode");
        return pose != null
                && pose.shadowReference == FlightBodyPose.ShadowReference.FREE;
    }
}

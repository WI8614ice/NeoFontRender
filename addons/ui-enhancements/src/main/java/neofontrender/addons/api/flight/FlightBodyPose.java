package neofontrender.addons.api.flight;

import java.util.Objects;

/**
 * Complete body pose and declared local basis supplied by a flight mode.
 *
 * <p>The one-argument constructor keeps the original API 9 interpretation: the quaternion maps
 * aircraft-local +Z forward to world space. Use {@link #modelUpY(FlightAttitude)} when the
 * quaternion directly maps an upright player model's local +Y up axis to world space.</p>
 */
public final class FlightBodyPose {
    /** Explicit owner-provided pose state; a cardinal-looking quaternion does not establish support. */
    public enum ShadowReference { UNSPECIFIED, FREE, AXIAL }
    /**
     * LEGACY_AIRCRAFT_FORWARD_Z preserves the original API meaning: local +Z is the
     * aircraft's longitudinal forward axis. MODEL_UP_Y means the quaternion maps a
     * standing player-model basis, whose up axis is local +Y, directly to world space.
     */
    public enum Basis {
        LEGACY_AIRCRAFT_FORWARD_Z,
        MODEL_UP_Y
    }

    public final FlightAttitude attitude;
    public final Basis basis;
    /** Optional sampled world-space head orientation; null preserves the neutral legacy head. */
    public final FlightAttitude headWorldAttitude;
    /** World-axis displacement from interpolated entity transport to the sampled model root. */
    public final FlightVector rootWorldOffset;
    /** Optional physical display geometry from this exact provider sample; absent for legacy providers. */
    public final FlightBodyGeometry geometry;
    public final ShadowReference shadowReference;
    /** Accepted world axis for a constrained body, independent of visual reference interpolation. */
    public final FlightVector shadowAxisUp;

    /** Keeps the API 9 JVM descriptor and aircraft-local +Z interpretation. */
    public FlightBodyPose(FlightAttitude attitude) {
        this(attitude, Basis.LEGACY_AIRCRAFT_FORWARD_Z, null);
    }

    public FlightBodyPose(FlightAttitude attitude, Basis basis) {
        this(attitude, basis, null);
    }

    public FlightBodyPose(FlightAttitude attitude, Basis basis,
                          FlightAttitude headWorldAttitude) {
        this(attitude, basis, headWorldAttitude, new FlightVector(0.0D, 0.0D, 0.0D));
    }

    private FlightBodyPose(FlightAttitude attitude, Basis basis,
                           FlightAttitude headWorldAttitude, FlightVector rootWorldOffset) {
        this(attitude, basis, headWorldAttitude, rootWorldOffset, null);
    }

    private FlightBodyPose(FlightAttitude attitude, Basis basis,
                           FlightAttitude headWorldAttitude, FlightVector rootWorldOffset, FlightBodyGeometry geometry) {
        this(attitude, basis, headWorldAttitude, rootWorldOffset, geometry, ShadowReference.UNSPECIFIED, null);
    }

    private FlightBodyPose(FlightAttitude attitude, Basis basis,
                           FlightAttitude headWorldAttitude, FlightVector rootWorldOffset, FlightBodyGeometry geometry,
                           ShadowReference shadowReference, FlightVector shadowAxisUp) {
        this.attitude = Objects.requireNonNull(attitude, "attitude");
        this.basis = Objects.requireNonNull(basis, "basis");
        this.headWorldAttitude = headWorldAttitude;
        this.rootWorldOffset = Objects.requireNonNull(rootWorldOffset, "rootWorldOffset");
        this.geometry = geometry;
        this.shadowReference = shadowReference;
        this.shadowAxisUp = shadowAxisUp;
    }

    /** Creates a pose whose quaternion directly maps the model's local +Y up axis to world space. */
    public static FlightBodyPose modelUpY(FlightAttitude attitude) {
        return new FlightBodyPose(attitude, Basis.MODEL_UP_Y);
    }

    /** Creates a model-up-Y pose with the head's world orientation from the same provider sample. */
    public static FlightBodyPose modelUpY(FlightAttitude bodyWorldAttitude,
                                          FlightAttitude headWorldAttitude) {
        return new FlightBodyPose(bodyWorldAttitude, Basis.MODEL_UP_Y,
                Objects.requireNonNull(headWorldAttitude, "headWorldAttitude"));
    }

    /** Samples body, head and physical-feet root together; the offset is translated before Q. */
    public static FlightBodyPose modelUpY(FlightAttitude bodyWorldAttitude,
                                          FlightAttitude headWorldAttitude,
                                          FlightVector rootWorldOffset) {
        return new FlightBodyPose(bodyWorldAttitude, Basis.MODEL_UP_Y,
                Objects.requireNonNull(headWorldAttitude, "headWorldAttitude"), rootWorldOffset);
    }

    /** Adds sampled capsule/eye to this pose without changing the legacy factory descriptors. */
    public FlightBodyPose withGeometry(FlightBodyGeometry geometry) {
        return new FlightBodyPose(attitude, basis, headWorldAttitude, rootWorldOffset,
                Objects.requireNonNull(geometry, "geometry"), shadowReference, shadowAxisUp);
    }

    /** Free pose never emits the axial disc, including while its quaternion happens to be cardinal. */
    public FlightBodyPose withFreeShadowReference() {
        return new FlightBodyPose(attitude, basis, headWorldAttitude, rootWorldOffset, geometry,
                ShadowReference.FREE, null);
    }

    /** Uses an actual accepted six-axis reference, rather than G intent or a camera-derived axis. */
    public FlightBodyPose withAxialShadowReference(FlightVector up) {
        Objects.requireNonNull(up, "up");
        if (!((Math.abs(up.x) == 1 && up.y == 0 && up.z == 0)
                || (up.x == 0 && Math.abs(up.y) == 1 && up.z == 0)
                || (up.x == 0 && up.y == 0 && Math.abs(up.z) == 1)))
            throw new IllegalArgumentException("Accepted cardinal shadow axis required");
        return new FlightBodyPose(attitude, basis, headWorldAttitude, rootWorldOffset, geometry,
                ShadowReference.AXIAL, up);
    }
}

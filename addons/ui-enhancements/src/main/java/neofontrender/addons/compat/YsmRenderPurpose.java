package neofontrender.addons.compat;

import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.flight.PlayerBodyRenderScope;

import java.util.Objects;
import java.util.function.Supplier;

/** Purpose comes from the HUD call site, never from player identity or render scale. */
public final class YsmRenderPurpose {
    public enum Purpose { WORLD, HUD_PREVIEW, GUI_PREVIEW, UNCLASSIFIED }
    private static final ThreadLocal<Boolean> HUD_PREVIEW = new ThreadLocal<>();
    private static final ThreadLocal<Purpose> CALL_PURPOSE = new ThreadLocal<>();

    private YsmRenderPurpose() { }

    public static Purpose currentPurpose() {
        if (Boolean.TRUE.equals(HUD_PREVIEW.get())) return Purpose.HUD_PREVIEW;
        Purpose purpose = CALL_PURPOSE.get();
        return purpose == null ? Purpose.UNCLASSIFIED : purpose;
    }

    public static void withWorldRender(PlayerBodyRenderScope.RenderCall render) throws Throwable {
        withPurpose(Purpose.WORLD, render);
    }

    public static void withGuiPreview(PlayerBodyRenderScope.RenderCall render) throws Throwable {
        withPurpose(Purpose.GUI_PREVIEW, render);
    }

    private static void withPurpose(Purpose purpose, PlayerBodyRenderScope.RenderCall render) throws Throwable {
        Purpose previous = CALL_PURPOSE.get();
        CALL_PURPOSE.set(purpose);
        try { render.run(); }
        finally {
            if (previous == null) CALL_PURPOSE.remove(); else CALL_PURPOSE.set(previous);
        }
    }

    public static void withHudPreview(PlayerBodyRenderScope.RenderCall render) throws Throwable {
        Objects.requireNonNull(render, "render");
        Boolean previous = HUD_PREVIEW.get();
        HUD_PREVIEW.set(Boolean.TRUE);
        try {
            render.run();
        } finally {
            if (previous == null) HUD_PREVIEW.remove();
            else HUD_PREVIEW.set(previous);
        }
    }

    public static boolean usesNativePreview(boolean previewFollowGravity) {
        return Boolean.TRUE.equals(HUD_PREVIEW.get()) && !previewFollowGravity;
    }

    public static boolean usesNativePreview() {
        return Boolean.TRUE.equals(HUD_PREVIEW.get()) && !YsmPoseConfig.previewFollowGravity();
    }

    /** Bypass all world-render mutations, while fencing an enclosing sampled entity pose. */
    public static boolean renderNativePreview(Object entity, PlayerBodyRenderScope.RenderCall render)
            throws Throwable {
        return renderNativePreview(entity, usesNativePreview(), render);
    }

    static boolean renderNativePreview(Object entity, boolean nativePreview,
                                       PlayerBodyRenderScope.RenderCall render) throws Throwable {
        if (!nativePreview) return false;
        PlayerBodyRenderScope.withSampledPose(entity, () -> null, render);
        return true;
    }

    public static FlightBodyPose sampleBodyPose(boolean enabled, boolean previewFollowGravity,
                                               Supplier<FlightBodyPose> sample) {
        Objects.requireNonNull(sample, "sample");
        return enabled && !usesNativePreview(previewFollowGravity) ? sample.get() : null;
    }
}

package neofontrender.addons.api.flight;

import neofontrender.addons.shadows.PlayerShadowConfig;

/** Shared selection used by settings screens and the player shadow renderer. */
public final class PlayerShadowApi {
    private PlayerShadowApi() {}

    public static PlayerShadowMode mode() {
        return PlayerShadowConfig.mode();
    }

    /** Client-thread operation; false means saving failed and the previous selection is retained. */
    public static boolean select(PlayerShadowMode mode) {
        return PlayerShadowConfig.select(mode);
    }
}

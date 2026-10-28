package neofontrender.addons.shadows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import neofontrender.addons.api.flight.PlayerShadowMode;
import neofontrender.addons.ui.NfrUiEnhancements;
import neofontrender.api.config.NfrConfigApi;
import neofontrender.api.config.NfrConfigFile;

/** One live client setting shared by file reloads and in-game selection. */
public final class PlayerShadowConfig {
    private record Stamp(FileTime modified, long size) {}
    private static final String FILE = "neofontrender-player-shadow.toml";
    private static PlayerShadowMode current = PlayerShadowMode.AXIAL_DISC;
    private static Path path;
    private static Stamp attemptedStamp;
    private static boolean statFailureReported;

    private PlayerShadowConfig() {}
    public static PlayerShadowMode mode() { return current; }

    public static void load() {
        try (NfrConfigFile file = open()) {
            file.define("mode", PlayerShadowMode.AXIAL_DISC.name(),
                    "AXIAL_DISC (no disc in free attitude) or REAL_PLAYER_PROJECTION. Live reload; client only.");
            current = read(file);
            file.save();
            path = file.path();
        }
        attemptedStamp = null;
    }

    public static boolean select(PlayerShadowMode next) {
        Objects.requireNonNull(next, "mode");
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("Player shadow selection requires the client thread");
        if (path == null) throw new IllegalStateException("Player shadow config is not initialized");
        try (NfrConfigFile file = open()) {
            file.set("mode", next.name()).save();
        } catch (RuntimeException error) {
            NfrUiEnhancements.LOGGER.error("Player shadow mode save failed; retaining selection " + current, error);
            return false;
        }
        current = next;
        attemptedStamp = null;
        return true;
    }

    /** Only metadata is read until an external edit is observed. Rejected edits keep the last valid mode. */
    public static void poll() {
        if (path == null) return;
        Stamp observed;
        try {
            observed = stamp();
            statFailureReported = false;
        } catch (IOException error) {
            if (!statFailureReported) {
                statFailureReported = true;
                NfrUiEnhancements.LOGGER.error("Player shadow config stat failed: " + path, error);
            }
            return;
        }
        if (observed.equals(attemptedStamp)) return;
        attemptedStamp = observed;
        try (NfrConfigFile file = open()) {
            PlayerShadowMode next = read(file);
            if (!observed.equals(stamp())) { attemptedStamp = null; return; }
            current = next;
            NfrUiEnhancements.LOGGER.info("Reloaded player shadow mode {} from {}", next, path);
        } catch (IOException | RuntimeException error) {
            NfrUiEnhancements.LOGGER.error("Player shadow config reload rejected; retaining selection " + current, error);
        }
    }

    private static PlayerShadowMode read(NfrConfigFile file) {
        Object raw = file.getValue("mode", PlayerShadowMode.AXIAL_DISC.name());
        if (!(raw instanceof String value)) throw new IllegalArgumentException(FILE + " mode must be a string");
        return PlayerShadowMode.valueOf(value);
    }

    private static NfrConfigFile open() {
        return NfrConfigApi.builder(NfrUiEnhancements.MOD_ID).fileName(FILE).open();
    }

    private static Stamp stamp() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new Stamp(attributes.lastModifiedTime(), attributes.size());
    }
}

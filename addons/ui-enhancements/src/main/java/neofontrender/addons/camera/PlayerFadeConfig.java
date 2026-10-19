package neofontrender.addons.camera;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import neofontrender.api.config.NfrConfigApi;
import neofontrender.api.config.NfrConfigFile;
import neofontrender.addons.ui.NfrUiEnhancements;

/** Dedicated live settings; reloading never replaces the shared UIE configuration store. */
final class PlayerFadeConfig {
    record Settings(double outsideDistance, double insideDepth, double minimumOpacity) {}
    private record Stamp(FileTime modified, long size) {}
    private static final String FILE = "neofontrender-player-fade.toml";
    private static Settings current = new Settings(0.1D, 0.3D, 0.0D);
    private static Path path;
    private static Stamp attemptedStamp;
    private static boolean statFailureReported;

    private PlayerFadeConfig() {}
    static Settings current() { return current; }

    static void load() {
        try (NfrConfigFile file = open()) {
            file.define("outsideDistance", 0.1D, "Blocks outside the player collision bounds where fading begins. Live reload.")
                    .define("insideDepth", 0.3D, "Blocks inside the collision bounds where minimum opacity is reached. Live reload.")
                    .define("minimumOpacityPercent", 0.0D, "0 is fully transparent; 100 is fully opaque. Live reload.");
            current = read(file);
            file.save();
            path = file.path();
        }
        try { attemptedStamp = stamp(); }
        catch (IOException error) { throw new IllegalStateException("Cannot stat player fade config " + path, error); }
    }

    /** Client-thread polling reads file metadata only until an external edit is observed. */
    static void poll() {
        if (path == null) return;
        Stamp observed;
        try {
            observed = stamp();
            statFailureReported = false;
        } catch (IOException error) {
            if (!statFailureReported) {
                statFailureReported = true;
                NfrUiEnhancements.LOGGER.error("Player fade config stat failed; retaining last valid settings: " + path, error);
            }
            return;
        }
        if (observed.equals(attemptedStamp)) return;
        attemptedStamp = observed;
        try (NfrConfigFile file = open()) {
            Settings next = read(file);
            // A save still in progress is retried on the next tick, without installing a mixed read.
            if (!observed.equals(stamp())) { attemptedStamp = null; return; }
            current = next;
            NfrUiEnhancements.LOGGER.info("Reloaded player fade config {}: outside={}, inside={}, minimumOpacity={}",
                    path, next.outsideDistance(), next.insideDepth(), next.minimumOpacity());
        } catch (IOException | RuntimeException error) {
            NfrUiEnhancements.LOGGER.error("Player fade config reload rejected; retaining last valid settings: " + path, error);
        }
    }

    private static NfrConfigFile open() {
        return NfrConfigApi.builder(NfrUiEnhancements.MOD_ID).fileName(FILE).open();
    }
    private static Stamp stamp() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new Stamp(attributes.lastModifiedTime(), attributes.size());
    }
    private static Settings read(NfrConfigFile file) {
        return new Settings(number(file, "outsideDistance", 0.1D, 0.0D, 32.0D),
                number(file, "insideDepth", 0.3D, 0.001D, 32.0D),
                number(file, "minimumOpacityPercent", 0.0D, 0.0D, 100.0D) / 100.0D);
    }
    private static double number(NfrConfigFile file, String key, double fallback, double min, double max) {
        Object value = file.getValue(key, fallback);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max)
            throw new IllegalArgumentException("Invalid " + FILE + " key " + key + "; expected finite number in [" + min + ", " + max + "]");
        return number.doubleValue();
    }
}

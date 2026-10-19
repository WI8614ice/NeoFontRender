package neofontrender.addons.compat;

import net.minecraft.launchwrapper.Launch;
import neofontrender.api.config.NfrConfigFile;
import neofontrender.internal.nightconfig.core.file.CommentedFileConfig;
import neofontrender.internal.nightconfig.toml.TomlFormat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent opt-out for the optional YSM body-pose renderer integration. */
public final class YsmPoseConfig {
    private static final String KEY = "compat.ysmBodyPose.enabled";
    private static final String FILE_NAME = "neofontrender-ui-enhancements.toml";
    private static final String COMMENT =
            "Apply flight body poses in the verified YSM player renderer. Restart after changing.";
    private static Settings cachedSettings;
    private static final String PREVIEW_KEY = "compat.ysmBodyPose.previewFollowGravity";
    record Settings(boolean enabled, boolean previewFollowGravity) {}

    private YsmPoseConfig() {}

    /** Register before any shared-store save: lazy mixin gating can run after that store opens. */
    public static void defineDefaults(NfrConfigFile file) {
        file.define(KEY, true, COMMENT);
        file.define(PREVIEW_KEY, false,
                "Follow gravity in the YSM self HUD preview. Default keeps the preview upright. Restart after changing.");
    }

    public static synchronized boolean enabled() {
        return settings().enabled();
    }

    public static synchronized boolean previewFollowGravity() {
        return settings().previewFollowGravity();
    }

    private static Settings settings() {
        if (cachedSettings != null) return cachedSettings;
        File gameDirectory = Launch.minecraftHome;
        if (gameDirectory == null) {
            throw new IllegalStateException("Cannot read early UI Enhancements config before "
                    + "Launch.minecraftHome is initialized");
        }
        cachedSettings = readSettings(gameDirectory.toPath());
        return cachedSettings;
    }

    static boolean readOrCreate(Path gameDirectory) {
        return readSettings(gameDirectory).enabled();
    }

    static Settings readSettings(Path gameDirectory) {
        Path path = gameDirectory.resolve("config").resolve(FILE_NAME).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) Files.createFile(path);
            try (CommentedFileConfig file = CommentedFileConfig.builder(path, TomlFormat.instance())
                    .sync().preserveInsertionOrder().build()) {
                file.load();
                if (!file.contains(KEY)) {
                    file.set(KEY, true);
                    file.setComment(KEY, COMMENT);
                    file.save();
                }
                if (!file.contains(PREVIEW_KEY)) {
                    file.set(PREVIEW_KEY, false);
                    file.setComment(PREVIEW_KEY, "Follow gravity in the YSM self HUD preview. Default keeps the preview upright. Restart after changing.");
                    file.save();
                }
                Object preview = file.get(PREVIEW_KEY);
                if (!(preview instanceof Boolean)) {
                    throw new IllegalStateException("Invalid UI Enhancements config " + path
                            + " key " + PREVIEW_KEY + ": expected a TOML boolean");
                }
                Object value = file.get(KEY);
                if (!(value instanceof Boolean enabled)) {
                    throw new IllegalStateException("Invalid UI Enhancements config " + path
                            + " key " + KEY + ": expected a TOML boolean, found "
                            + (value == null ? "null" : value.getClass().getSimpleName()));
                }
                return new Settings(enabled, (Boolean) preview);
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Could not read early UI Enhancements config "
                    + path + " key " + KEY, failure);
        }
    }
}

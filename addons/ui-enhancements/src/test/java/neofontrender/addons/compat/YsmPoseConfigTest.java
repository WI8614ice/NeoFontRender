package neofontrender.addons.compat;

import neofontrender.internal.nightconfig.core.file.CommentedFileConfig;
import neofontrender.internal.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmPoseConfigTest {
    @TempDir
    Path gameDirectory;

    @Test
    void earlyReadCreatesPersistentDefaultAndHonorsOptOut() throws Exception {
        Path config = gameDirectory.resolve("config/neofontrender-ui-enhancements.toml");

        assertTrue(YsmPoseConfig.readOrCreate(gameDirectory));
        assertTrue(Files.isRegularFile(config));
        try (CommentedFileConfig persisted = CommentedFileConfig.builder(config, TomlFormat.instance())
                .preserveInsertionOrder().build()) {
            persisted.load();
            String serialized = Files.readString(config);
            assertEquals(Boolean.TRUE, persisted.get("compat.ysmBodyPose.enabled"),
                    "Persisted early config was not readable:\n" + serialized);
            assertEquals(Boolean.FALSE, persisted.get("compat.ysmBodyPose.previewFollowGravity"));
        }

        Files.writeString(config, "[compat.ysmBodyPose]\nenabled = false\n");
        assertFalse(YsmPoseConfig.readOrCreate(gameDirectory));
    }

    @Test
    void previewDefaultMigrationAndOptInPreserveExistingKeys() throws Exception {
        Path config = gameDirectory.resolve("config/neofontrender-ui-enhancements.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[compat.ysmBodyPose]\nenabled = false\nother = 42\n");
        YsmPoseConfig.Settings migrated = YsmPoseConfig.readSettings(gameDirectory);
        assertFalse(migrated.enabled());
        assertFalse(migrated.previewFollowGravity());
        try (CommentedFileConfig persisted = CommentedFileConfig.builder(config, TomlFormat.instance()).sync().build()) {
            persisted.load();
            assertEquals(Boolean.FALSE, persisted.get("compat.ysmBodyPose.previewFollowGravity"));
            assertEquals(42, (Integer) persisted.get("compat.ysmBodyPose.other"));
            persisted.set("compat.ysmBodyPose.previewFollowGravity", true);
            persisted.save();
        }
        YsmPoseConfig.Settings optIn = YsmPoseConfig.readSettings(gameDirectory);
        assertFalse(optIn.enabled());
        assertTrue(optIn.previewFollowGravity());
        Files.writeString(config, "[compat.ysmBodyPose]\nenabled = true\npreviewFollowGravity = 1\n");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> YsmPoseConfig.readSettings(gameDirectory));
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("previewFollowGravity"));
    }

    @Test
    void malformedTomlFailsWithItsOriginalCause() throws Exception {
        Path config = gameDirectory.resolve("config/neofontrender-ui-enhancements.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[compat.ysmBodyPose]\nenabled = [\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> YsmPoseConfig.readOrCreate(gameDirectory));
        assertTrue(failure.getMessage().contains(config.toAbsolutePath().normalize().toString()));
        assertNotNull(failure.getCause());
    }
}

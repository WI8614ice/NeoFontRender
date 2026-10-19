package neofontrender.addons.compat;

import net.minecraftforge.fml.common.Loader;
import neofontrender.api.config.NfrConfigApi;
import neofontrender.api.config.NfrConfigFile;
import neofontrender.api.config.NfrConfigStorage;
import neofontrender.internal.nightconfig.core.file.CommentedFileConfig;
import neofontrender.internal.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class YsmConfigPersistenceTest {
    @TempDir Path gameDirectory;

    @Test void earlyDefaultsSurviveAnAlreadyOpenNormalStoreSaveAndRestart() throws Exception {
        Path directory = gameDirectory.resolve("config");
        Files.createDirectories(directory);
        Path path = directory.resolve("neofontrender-ui-enhancements.toml");
        Files.writeString(path, "[compat.ysmBodyPose]\nenabled = true\n[unrelated]\nkeep = 42\n");
        // Forge has no public config-directory setter outside loadMods. Set only this framework
        // fixture field, then exercise the real NFR public store/serializer without a mock store.
        Loader loader = Loader.instance();
        var configDir = Loader.class.getDeclaredField("canonicalConfigDir");
        configDir.setAccessible(true);
        Object previous = configDir.get(loader);
        configDir.set(loader, directory.toFile());
        try {
        try (NfrConfigFile normal = NfrConfigApi.builder("neofontrenderuienhancements")
                .storage(NfrConfigStorage.LAYERED).fileName("neofontrender-ui-enhancements.toml").open()) {
            YsmPoseConfig.defineDefaults(normal);
            // Exact startup order: normal store opens first; the optional target's lazy gate
            // subsequently adds the early key directly to disk; a normal module saves later.
            assertFalse(YsmPoseConfig.readSettings(gameDirectory).previewFollowGravity());
            normal.set("normal.marker", "saved");
            normal.save();
            awaitMarker(path, "saved");
            try (CommentedFileConfig disk = CommentedFileConfig.builder(path, TomlFormat.instance()).sync().build()) {
                disk.load();
                assertEquals(Boolean.FALSE, disk.get("compat.ysmBodyPose.previewFollowGravity"));
                assertEquals(42, (Integer) disk.get("unrelated.keep"));
            }
            assertFalse(YsmPoseConfig.readSettings(gameDirectory).previewFollowGravity());
        }
            for (boolean follow : new boolean[]{true, false}) {
                for (boolean enabled : new boolean[]{false, true}) {
                    String marker = "follow=" + follow + ";enabled=" + enabled;
                    try (NfrConfigFile normal = NfrConfigApi.builder("neofontrenderuienhancements")
                            .storage(NfrConfigStorage.LAYERED).fileName("neofontrender-ui-enhancements.toml").open()) {
                    YsmPoseConfig.defineDefaults(normal);
                    normal.set("compat.ysmBodyPose.previewFollowGravity", follow);
                    normal.set("compat.ysmBodyPose.enabled", enabled);
                    normal.set("normal.marker", marker);
                    normal.save();
                    awaitMarker(path, marker);
                    }
                    // A fresh shared store and the independent early restart reader must agree.
                    try (NfrConfigFile restarted = NfrConfigApi.builder("neofontrenderuienhancements")
                            .storage(NfrConfigStorage.LAYERED).fileName("neofontrender-ui-enhancements.toml").open()) {
                        YsmPoseConfig.defineDefaults(restarted);
                        assertEquals(follow, restarted.getBoolean("compat.ysmBodyPose.previewFollowGravity", !follow));
                        assertEquals(enabled, restarted.getBoolean("compat.ysmBodyPose.enabled", !enabled));
                        assertEquals(42, restarted.getInt("unrelated.keep", 0, 0, 100));
                        restarted.set("normal.marker", marker + ";restart");
                        restarted.save();
                        awaitMarker(path, marker + ";restart");
                    }
                    var earlyRestart = YsmPoseConfig.readSettings(gameDirectory);
                    assertEquals(follow, earlyRestart.previewFollowGravity());
                    assertEquals(enabled, earlyRestart.enabled());
                }
            }
        } finally {
            configDir.set(loader, previous);
        }
    }

    private static void awaitMarker(Path path, String expected) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        do {
            try (CommentedFileConfig disk = CommentedFileConfig.builder(path, TomlFormat.instance()).sync().build()) {
                disk.load();
                if (expected.equals(disk.get("normal.marker"))) return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        fail("Real normal config serializer did not save marker");
    }
}

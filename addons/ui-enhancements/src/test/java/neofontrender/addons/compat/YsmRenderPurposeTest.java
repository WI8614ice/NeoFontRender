package neofontrender.addons.compat;

import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import neofontrender.addons.flight.PlayerBodyRenderScope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YsmRenderPurposeTest {
    private final FlightBodyPose worldPose = FlightBodyPose.modelUpY(
            FlightAttitude.fromMinecraftDegrees(0, 0, 90),
            FlightAttitude.fromMinecraftDegrees(25, 40, 90), new FlightVector(-.9, .3, 0));

    @Test void defaultHudPreviewUsesNativeRootAndHeadWithoutSamplingWorldProvider() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        Object player = new Object();
        YsmRenderPurpose.withHudPreview(() -> PlayerBodyRenderScope.withSampledPose(player,
                () -> YsmRenderPurpose.sampleBodyPose(true, false, () -> {
                    calls.incrementAndGet();
                    return worldPose;
                }), () -> {
                    var context = PlayerBodyRenderScope.current(player);
                    assertFalse(context.active(), "native preview cannot own root rotation/offset or head angles");
                    assertNull(context.pose());
                    assertNull(context.transform());
                }));
        assertEquals(0, calls.get());
        assertNull(PlayerBodyRenderScope.current());
    }

    @Test void outerNativeBypassDoesNotExecuteWorldMutationsAndFencesOuterPose() throws Throwable {
        Object player = new Object();
        float[] fields = {1, 2, 3, 4, 5, 6};
        float[] previous = fields.clone();
        RuntimeException sentinel = new IllegalStateException("native render failure");
        PlayerBodyRenderScope.withSampledPose(player, () -> worldPose, () -> {
            var outer = PlayerBodyRenderScope.current(player);
            YsmRenderPurpose.withHudPreview(() -> {
                assertSame(sentinel, assertThrows(RuntimeException.class, () -> {
                    if (YsmRenderPurpose.renderNativePreview(player,
                            YsmRenderPurpose.usesNativePreview(false), () -> {
                                assertFalse(PlayerBodyRenderScope.current(player).active());
                                assertArrayEquals(previous, fields);
                                throw sentinel;
                            })) return;
                    java.util.Arrays.fill(fields, 99); // cursor/head/roll branch must be bypassed
                }));
                assertSame(outer, PlayerBodyRenderScope.current(player));
            });
        });
        assertArrayEquals(previous, fields);
        assertNull(PlayerBodyRenderScope.current());
        assertFalse(YsmRenderPurpose.renderNativePreview(player, false, () -> { throw new AssertionError(); }));
    }

    @Test void explicitFollowRestoresTheExactWorldPoseAndEnabledAlwaysVetoes() throws Throwable {
        YsmRenderPurpose.withHudPreview(() -> {
            assertSame(worldPose, YsmRenderPurpose.sampleBodyPose(true, true, () -> worldPose));
            assertNull(YsmRenderPurpose.sampleBodyPose(false, true, () -> { throw new AssertionError(); }));
        });
        assertSame(worldPose, YsmRenderPurpose.sampleBodyPose(true, false, () -> worldPose),
                "world local and remote renders retain their pose outside the explicit HUD purpose");
    }

    @Test void providerFailurePreservesCauseAndRestoresBothScopes() {
        RuntimeException sentinel = new IllegalStateException("provider failure");
        assertSame(sentinel, assertThrows(RuntimeException.class, () ->
                YsmRenderPurpose.withHudPreview(() -> PlayerBodyRenderScope.withSampledPose(new Object(),
                        () -> YsmRenderPurpose.sampleBodyPose(true, true, () -> { throw sentinel; }),
                        () -> { throw new AssertionError("failed provider cannot render"); }))));
        assertNull(PlayerBodyRenderScope.current());
        assertFalse(YsmRenderPurpose.usesNativePreview(false));
    }

    @Test void nestedAndThrowingPreviewRestoresPurposeAndEntityScope() throws Throwable {
        RuntimeException sentinel = new IllegalStateException("preview renderer failed");
        YsmRenderPurpose.withHudPreview(() -> {
            assertSame(sentinel, assertThrows(RuntimeException.class, () ->
                    YsmRenderPurpose.withHudPreview(() -> { throw sentinel; })));
            assertNull(YsmRenderPurpose.sampleBodyPose(true, false, () -> worldPose));
        });
        Object player = new Object();
        assertSame(sentinel, assertThrows(RuntimeException.class, () -> YsmRenderPurpose.withHudPreview(() ->
                PlayerBodyRenderScope.withSampledPose(player,
                        () -> YsmRenderPurpose.sampleBodyPose(true, false, () -> worldPose),
                        () -> { throw sentinel; }))));
        assertNull(PlayerBodyRenderScope.current());
        assertSame(worldPose, YsmRenderPurpose.sampleBodyPose(true, false, () -> worldPose));
    }
}

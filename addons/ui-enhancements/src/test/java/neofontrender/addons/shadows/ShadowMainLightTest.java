package neofontrender.addons.shadows;

import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: pure actual-source selection contract; does not simulate geometry visibility. */
class ShadowMainLightTest {
    private static ShadowMainLight.Candidate block(int x, double strength) {
        return new ShadowMainLight.Candidate(new ShadowMainLight.Id(ShadowMainLight.Kind.BLOCK, new BlockPos(x, 5, 0)),
                x + .5, 5.5, .5, strength);
    }
    private static ShadowMainLight.Evaluated visible(ShadowMainLight.Candidate light) {
        return new ShadowMainLight.Evaluated(light, ShadowMainLight.Visibility.VISIBLE);
    }

    @Test void onlyProvedVisibleSourcesParticipateAndIdentityIsHeldThroughSmallChanges() {
        Object world = new Object();
        var select = new ShadowMainLight.Selection();
        var a = block(1, .5); var b = block(2, .9);
        assertEquals(a, select.choose(world, 0, List.of(visible(a),
                new ShadowMainLight.Evaluated(b, ShadowMainLight.Visibility.UNAVAILABLE))));
        assertEquals(a, select.choose(world, 1, List.of(visible(a), visible(b))));
        assertEquals(b, select.choose(world, 8, List.of(visible(a), visible(b))));
        var slightlyStrongerA = block(1, .95);
        assertEquals(b, select.choose(world, 20, List.of(visible(slightlyStrongerA), visible(b))));
        assertEquals(slightlyStrongerA, select.choose(world, 21, List.of(visible(slightlyStrongerA),
                new ShadowMainLight.Evaluated(b, ShadowMainLight.Visibility.OCCLUDED))));
    }

    @Test void worldChangeTimeRewindAndLostSourcesCannotKeepStaleLight() {
        Object world = new Object(); var select = new ShadowMainLight.Selection();
        var a = block(1, .4); var b = block(2, .8);
        select.choose(world, 100, List.of(visible(a)));
        assertEquals(b, select.choose(world, 90, List.of(visible(a), visible(b))));
        assertEquals(a, select.choose(new Object(), 90, List.of(visible(a))));
        assertNull(select.choose(world, 91, List.of()));
    }

    @Test void exactTwentyFivePercentBoundaryHasNoExtraAbsoluteOffset() {
        Object world = new Object();
        for (double strength : new double[] {.499, .5, .51}) {
            var select = new ShadowMainLight.Selection();
            var a = block(1, .4); var b = block(2, strength);
            assertEquals(a, select.choose(world, 0, List.of(visible(a))));
            assertEquals(a, select.choose(world, 7, List.of(visible(a), visible(b))));
            assertEquals(strength < .5 ? a : b, select.choose(world, 8, List.of(visible(a), visible(b))));
        }
    }

    @Test void blockedBestAndHeldWeakSourceCannotPermanentlyStarveAVisibleStrongerSource() {
        Object world = new Object();
        var select = new ShadowMainLight.Selection();
        var blocked = block(1, 1); var held = block(2, .2); var stronger = block(3, .8);
        assertEquals(held, select.choose(world, 0, List.of(visible(held))));
        boolean reachedStronger = false;
        for (int round = 0; round < 3; round++) {
            var probes = select.probes(List.of(blocked, stronger, held));
            assertTrue(probes.size() <= 2);
            if (!reachedStronger) assertTrue(probes.contains(held), "held source still needs current proof");
            var evaluated = probes.stream().map(light -> new ShadowMainLight.Evaluated(light,
                    light == blocked ? ShadowMainLight.Visibility.OCCLUDED : ShadowMainLight.Visibility.VISIBLE)).toList();
            var selected = select.choose(world, 10 + round, evaluated);
            reachedStronger |= selected == stronger;
        }
        assertTrue(reachedStronger);
    }
}

package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.BlockPos;

/** Real source candidates and a stable choice after light-path visibility has actually been evaluated. */
public final class ShadowMainLight {
    public enum Kind { SUN, MOON, BLOCK }
    public enum SkyAvailability { VANILLA_CELESTIAL, NO_SKY, CUSTOM_SKY_UNAVAILABLE }
    public enum Visibility { VISIBLE, OCCLUDED, UNAVAILABLE }
    public record Id(Kind kind, BlockPos block) {
        public Id {
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.BLOCK) != (block != null)) throw new IllegalArgumentException("Only block lights have a block identity");
            if (block != null) block = block.toImmutable();
        }
    }
    /** xyz is a unit vector towards the sky light, or an absolute double world point for a block. */
    public record Candidate(Id id, double x, double y, double z, double strength) {
        public Candidate {
            Objects.requireNonNull(id, "id");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(strength) || strength <= 0 || strength > 1)
                throw new IllegalArgumentException("Finite positive light candidate required");
            if (id.kind != Kind.BLOCK && Math.abs(x * x + y * y + z * z - 1) > 1.0E-6)
                throw new IllegalArgumentException("Unit sky direction required");
        }
        public boolean directional() { return id.kind != Kind.BLOCK; }
    }
    public record Candidates(SkyAvailability skyAvailability, boolean completeBlockRegion, List<Candidate> lights) {
        public Candidates { lights = List.copyOf(lights); }
    }
    public record Evaluated(Candidate light, Visibility visibility) {
        public Evaluated { Objects.requireNonNull(light, "light"); Objects.requireNonNull(visibility, "visibility"); }
    }

    private ShadowMainLight() {}

    /** Reads cached emitters, never performs a per-frame block-volume scan or guesses direction from block light. */
    public static Candidates candidates(WorldClient world, float partialTick, double x, double y, double z,
                                         Collection<ShadowTerrainCache.Section> sections) {
        Objects.requireNonNull(world, "world");
        if (!Float.isFinite(partialTick) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Finite light sampling coordinates required");
        var lights = new LinkedHashMap<Id, Candidate>();
        SkyAvailability sky;
        if (!world.provider.hasSkyLight() || !world.provider.isSurfaceWorld()) sky = SkyAvailability.NO_SKY;
        else if (world.provider.getSkyRenderer() != null) sky = SkyAvailability.CUSTOM_SKY_UNAVAILABLE;
        else {
            sky = SkyAvailability.VANILLA_CELESTIAL;
            // Exactly RenderGlobal's R_y(-90 degrees) R_x(celestialAngle) applied to the sky's +Y sun.
            double angle = world.getCelestialAngle(partialTick) * Math.PI * 2;
            double sx = -Math.sin(angle), sy = Math.cos(angle);
            double weather = (1 - world.getRainStrength(partialTick)) * (1 - world.getThunderStrength(partialTick));
            double weight = sy > 0 ? sy * weather : -sy * weather * .15 * world.getCurrentMoonPhaseFactor();
            if (weight > 1.0E-4) {
                Id id = new Id(sy > 0 ? Kind.SUN : Kind.MOON, null);
                double sign = sy > 0 ? 1 : -1;
                lights.put(id, new Candidate(id, sx * sign, sy * sign, 0, Math.min(1, weight)));
            }
        }
        boolean complete = true;
        for (var section : sections) {
            if (section.availability() != ShadowTerrainCache.Availability.READY) { complete = false; continue; }
            for (var emitter : section.emitters()) {
                double lx = emitter.position().getX() + .5, ly = emitter.position().getY() + .5,
                        lz = emitter.position().getZ() + .5;
                double distance = Math.sqrt(square(lx - x) + square(ly - y) + square(lz - z));
                // Ranking weight only. This is not a claim that the source can illuminate through intervening geometry.
                double weight = (emitter.emission() - distance) / 15.0;
                if (weight > 0) {
                    Id id = new Id(Kind.BLOCK, emitter.position());
                    lights.put(id, new Candidate(id, lx, ly, lz, weight));
                }
            }
        }
        return new Candidates(sky, complete, new ArrayList<>(lights.values()));
    }

    private static double square(double x) { return x * x; }

    /** One instance per retained player; caller must evaluate visibility from actual light-space world depth first. */
    public static final class Selection {
        private Id selected;
        private long selectedAt;
        private long lastTick = Long.MIN_VALUE;
        private Object world;
        private int probeCursor;

        public Id selectedId() { return selected; }

        /** Re-prove the held source and rotate the second slot; a blocked high score cannot starve exploration. */
        public List<Candidate> probes(List<Candidate> ranked) {
            if (ranked.isEmpty()) return List.of();
            Candidate anchor = ranked.stream().filter(c -> c.id.equals(selected)).findFirst().orElse(ranked.getFirst());
            var others = ranked.stream().filter(c -> !c.id.equals(anchor.id)).toList();
            if (others.isEmpty()) return List.of(anchor);
            return List.of(anchor, others.get(Math.floorMod(probeCursor++, others.size())));
        }

        public Candidate choose(Object currentWorld, long tick, List<Evaluated> evaluated) {
            Objects.requireNonNull(currentWorld, "world");
            if (world != currentWorld || tick < lastTick) { selected = null; selectedAt = tick; world = currentWorld; }
            lastTick = tick;
            List<Candidate> visible = evaluated.stream().filter(item -> item.visibility == Visibility.VISIBLE)
                    .map(Evaluated::light).sorted(Comparator.comparingDouble(Candidate::strength).reversed()
                            .thenComparing(item -> item.id.kind).thenComparingLong(item -> item.id.block == null ? 0 : item.id.block.toLong()))
                    .toList();
            if (visible.isEmpty()) { selected = null; return null; }
            Candidate best = visible.getFirst();
            Candidate previous = visible.stream().filter(item -> item.id.equals(selected)).findFirst().orElse(null);
            // Hold the actual source identity through small score changes; never blend invented lamp positions/directions.
            if (previous != null && (tick - selectedAt < 8 || best.strength < previous.strength * 1.25)) return previous;
            if (!best.id.equals(selected)) { selected = best.id; selectedAt = tick; }
            return best;
        }
    }
}

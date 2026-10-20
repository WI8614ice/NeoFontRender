package neofontrender.addons.api.flight;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** Optional observation channel. It grants no flight, camera, input or render ownership. */
public final class FlightRenderDiagnostics {
    public record Observation(String physicalScope, long physicalRevision, int clientTick,
                              boolean requestedG, Boolean acknowledgedG, boolean effectiveG,
                              String gravityIdentity) {
        public Observation {
            Objects.requireNonNull(physicalScope, "physicalScope");
            Objects.requireNonNull(gravityIdentity, "gravityIdentity");
        }
    }

    private record Entry(WeakReference<Object> entity, WeakReference<Object> world, Observation observation) { }
    private static volatile Entry current;

    private FlightRenderDiagnostics() { }

    /** Publish only values already observed by the provider on the client thread. */
    public static void publish(Object entity, Object world, Observation observation) {
        current = new Entry(new WeakReference<>(Objects.requireNonNull(entity, "entity")),
                new WeakReference<>(Objects.requireNonNull(world, "world")),
                Objects.requireNonNull(observation, "observation"));
    }

    /** A stale actor, world or tick never supplies an inferred G state to a render. */
    public static Observation observe(Object entity, Object world, int clientTick) {
        if (entity == null || world == null) return null;
        Entry value = current;
        return value != null && value.entity().get() == entity && value.world().get() == world
                && value.observation().clientTick() == clientTick ? value.observation() : null;
    }
}

package neofontrender.addons.shadows;

import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: true point-source face coverage, no rendering or synthetic player. */
class ShadowProjectionPlanTest {
    @Test void closeSourceUsesSixFacesRatherThanDroppingReceiversBehindOneCone() {
        try (var frame = readyFrame()) {
            var region = new ShadowCompositePass.Region(8, 65, 8, 3);
            var source = new ShadowMainLight.Candidate(new ShadowMainLight.Id(ShadowMainLight.Kind.BLOCK,
                    new BlockPos(8, 64, 8)), 8.5, 64.5, 8.5, .8);
            var plan = ShadowProjectionPlan.create(region, source, frame.projection());
            assertEquals(6, plan.views().size());
            for (int axis = 0; axis < 3; axis++) for (int sign : new int[] {-1, 1}) {
                var p = new Vector4f((float) source.x() + (axis == 0 ? sign : 0),
                        (float) source.y() + (axis == 1 ? sign : 0),
                        (float) source.z() + (axis == 2 ? sign : 0), 1);
                assertTrue(plan.views().stream().anyMatch(view -> {
                    var clip = new Matrix4f().set(view.matrix()).transform(new Vector4f(p));
                    return clip.w > 0 && Math.abs(clip.x / clip.w) < 1e-5 && Math.abs(clip.y / clip.w) < 1e-5
                            && clip.z / clip.w > -1 && clip.z / clip.w < 1;
                }));
            }
        }
    }

    private static TerrainSceneFrame.WorldScope readyFrame() {
        var frame = TerrainSceneFrame.beginWorld(0, false);
        float[] identity = new Matrix4f().get(new float[16]);
        try (var ignored = TerrainSceneFrame.beginTerrain(0, 0, 0)) { TerrainSceneFrame.matrices(identity, identity); }
        TerrainSceneFrame.opaqueComplete();
        return frame;
    }
}

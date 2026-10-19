package neofontrender.addons.camera;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.NetworkManager;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import neofontrender.addons.api.camera.CameraApi;
import neofontrender.addons.api.camera.CameraEntityPickGeometry;
import neofontrender.addons.api.camera.CameraEntityPickGeometryProvider;
import neofontrender.addons.api.camera.CameraRegistration;
import neofontrender.addons.api.camera.CameraVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the production ray service, actual WorldClient chunk/entity queries and block ray tracing. */
class CameraEntityPickGeometryTest {
    private final List<CameraRegistration> registrations = new ArrayList<>();
    private int nextId = 100;

    @BeforeAll static void initializeRegistries() { Bootstrap.register(); }
    @AfterEach void closeProviders() { registrations.forEach(CameraRegistration::close); }

    @Test
    void apiVersionDeclaresEntityGeometryCapability() {
        assertEquals(4, CameraApi.getApiVersion());
    }

    @Test
    void horizontalBodyOnlyRayHitsAndOldUprightBoxOnlyRayMissesWithoutMutatingPhysics() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        Entity target = entity(world, 3.0D, 0.0D, 3.0D);
        AxisAlignedBB nativeBounds = target.getEntityBoundingBox();
        register("body", List.of(new CameraEntityPickGeometry(target,
                new AxisAlignedBB(2.1D, 0.0D, 2.7D, 3.9D, 0.6D, 3.3D))));
        assertSame(target, ray(camera, 2.2D, 0.3D, 6.0D).entityHit);
        assertNoEntity(ray(camera, 3.0D, 1.4D, 6.0D));
        assertSame(nativeBounds, target.getEntityBoundingBox(), "picking cannot replace native physics");
    }

    @Test
    void snapshotBoundsAlsoSupplyBroadphaseOutsideNativeSearchAndPreserveNearestReachAndWalls() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        Entity near = entity(world, 30.0D, 20.0D, 3.0D);
        Entity far = entity(world, 35.0D, 20.0D, 5.0D);
        register("body", List.of(new CameraEntityPickGeometry(far, box(2.0D, 5.0D)),
                new CameraEntityPickGeometry(near, box(2.0D, 3.0D))));
        assertSame(near, ray(camera, 2.2D, 0.3D, 7.0D).entityHit);
        assertNoEntity(ray(camera, 2.2D, 0.3D, 2.0D));
        assertTrue(world.setBlockState(new BlockPos(2, 0, 1), Blocks.STONE.getDefaultState()));
        RayTraceResult blocked = ray(camera, 2.2D, 0.3D, 7.0D);
        assertEquals(RayTraceResult.Type.BLOCK, blocked.typeOfHit);
        assertEquals(new BlockPos(2, 0, 1), blocked.getBlockPos());
    }

    @Test
    void explicitSuppressionCannotFallBackToOldBoxAndUnclaimedEntitiesRemainNative() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        Entity owned = entity(world, 3.0D, 0.0D, 3.0D);
        Entity nativeTarget = entity(world, 5.0D, 0.0D, 3.0D);
        register("suppressed", List.of(CameraEntityPickGeometry.suppressed(owned)));
        assertNoEntity(ray(camera, 3.0D, 1.4D, 6.0D));
        assertSame(nativeTarget, ray(camera, 5.0D, 1.4D, 6.0D).entityHit);
    }

    @Test
    void eachProviderIsSampledOnceAndReentrantRegistryChangeTakesEffectOnlyNextPick() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        Entity target = entity(world, 30.0D, 20.0D, 3.0D);
        AtomicInteger samples = new AtomicInteger();
        registrations.add(CameraApi.registerEntityPickGeometryProvider(new CameraEntityPickGeometryProvider() {
            @Override public ResourceLocation id() { return new ResourceLocation("test", "first"); }
            @Override public int priority() { return 10; }
            @Override public List<CameraEntityPickGeometry> sample(World sampleWorld, Entity excluded, float partial) {
                assertSame(world, sampleWorld);
                assertSame(camera, excluded);
                assertEquals(0.625F, partial);
                samples.incrementAndGet();
                register("later", List.of(CameraEntityPickGeometry.suppressed(target)));
                return List.of(new CameraEntityPickGeometry(target, box(2.0D, 3.0D)),
                        new CameraEntityPickGeometry(target, box(10.0D, 3.0D)));
            }
        }));
        assertSame(target, ray(camera, 2.2D, 0.3D, 6.0D).entityHit);
        assertEquals(1, samples.get(), "broadphase and final ray must share the first immutable sample");
        assertSame(target, ray(camera, 2.2D, 0.3D, 6.0D).entityHit,
                "highest priority first decision still owns this entity");
        assertEquals(2, samples.get());
    }

    @Test
    void deadCrossWorldAndReplacedEntityObjectsNeverContributeGeometry() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        Entity dead = entity(world, 30.0D, 20.0D, 3.0D);
        dead.setDead();
        Entity otherWorld = entity(world(), 30.0D, 20.0D, 3.0D);
        Entity removed = entity(world, 30.0D, 20.0D, 3.0D);
        world.removeEntityFromWorld(removed.getEntityId());
        register("stale", List.of(new CameraEntityPickGeometry(dead, box(2.0D, 3.0D)),
                new CameraEntityPickGeometry(otherWorld, box(2.0D, 3.0D)),
                new CameraEntityPickGeometry(removed, box(2.0D, 3.0D))));
        assertNoEntity(ray(camera, 2.2D, 0.3D, 6.0D));
    }

    @Test
    void providerFailureKeepsOriginalExceptionAndDoesNotManufactureNativeSuccess() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        IllegalStateException cause = new IllegalStateException("geometry sample failure");
        registrations.add(CameraApi.registerEntityPickGeometryProvider(new CameraEntityPickGeometryProvider() {
            @Override public ResourceLocation id() { return new ResourceLocation("test", "failure"); }
            @Override public List<CameraEntityPickGeometry> sample(World ignored, Entity excluded, float partial) {
                throw cause;
            }
        }));
        assertSame(cause, assertThrows(IllegalStateException.class,
                () -> ray(camera, 2.2D, 0.3D, 6.0D)));
    }

    @Test
    void broadphaseAndFinalIntersectionUseOneFrozenCollisionBorderSample() {
        WorldClient world = world();
        Entity camera = entity(world, 0.0D, 0.0D, 0.0D);
        BorderEntity target = new BorderEntity(world);
        target.setPosition(30.0D, 20.0D, 3.0D);
        world.addEntityToWorld(nextId++, target);
        register("border", List.of(new CameraEntityPickGeometry(target, box(2.3D, 3.0D))));
        assertSame(target, ray(camera, 2.2D, 0.3D, 6.0D).entityHit);
        assertEquals(1, target.borderSamples);
    }

    private void register(String id, List<CameraEntityPickGeometry> entries) {
        registrations.add(CameraApi.registerEntityPickGeometryProvider(new CameraEntityPickGeometryProvider() {
            @Override public ResourceLocation id() { return new ResourceLocation("test", id); }
            @Override public List<CameraEntityPickGeometry> sample(World world, Entity excluded, float partial) {
                return entries;
            }
        }));
    }

    private static AxisAlignedBB box(double x, double z) {
        return new AxisAlignedBB(x, 0.0D, z - 0.3D, x + 0.6D, 0.6D, z + 0.3D);
    }

    private static RayTraceResult ray(Entity camera, double x, double y, double reach) {
        return CameraPickingService.traceRay(camera, new CameraVector(x, y, 0.0D),
                new CameraVector(0.0D, 0.0D, 1.0D), reach, false, true, 0.625F);
    }

    private static void assertNoEntity(RayTraceResult hit) {
        assertTrue(hit == null || hit.typeOfHit != RayTraceResult.Type.ENTITY);
    }

    private Entity entity(WorldClient world, double x, double y, double z) {
        EntityArmorStand entity = new EntityArmorStand(world);
        entity.setPosition(x, y, z);
        world.addEntityToWorld(nextId++, entity);
        return entity;
    }

    private static WorldClient world() {
        NetHandlerPlayClient connection = new NetHandlerPlayClient(null, null, (NetworkManager) null,
                new GameProfile(UUID.randomUUID(), "pick-geometry-test"));
        WorldClient world = new HeadlessWorld(connection);
        for (int x = 0; x <= 2; x++) world.doPreChunk(x, 0, true);
        return world;
    }

    private static final class HeadlessWorld extends WorldClient {
        private HeadlessWorld(NetHandlerPlayClient connection) {
            super(connection, new WorldSettings(0L, GameType.SURVIVAL, false, false,
                    WorldType.DEFAULT), 0, EnumDifficulty.NORMAL, new Profiler());
        }

        @Override public Entity getEntityByID(int id) {
            // WorldClient's extra local-player alias requires a live Minecraft singleton.
            // These non-player fixtures use World's real ID map; chunk broadphase is unchanged.
            return entitiesById.lookup(id);
        }
    }

    private static final class BorderEntity extends EntityArmorStand {
        private int borderSamples;
        private BorderEntity(World world) { super(world); }
        @Override public float getCollisionBorderSize() {
            return borderSamples++ == 0 ? 0.2F : 0.0F;
        }
    }
}

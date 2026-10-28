package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.joml.Matrix4f;

/** Renders actual world and frozen player triangles into separate owned light-space depth targets. */
public final class ShadowLightDepthPass implements AutoCloseable {
    public enum Availability { RENDERED, INCOMPLETE_WORLD_COVERAGE, DYNAMIC_WORLD_MODEL_UNAVAILABLE,
        TRANSLUCENT_WORLD_UNAVAILABLE, NO_CASTER_GEOMETRY, NO_AXIAL_DISC, NO_SCENE_DEPTH, UNSUPPORTED_RECEIVER_DEPTH }
    public record Result(Availability availability, String detail, Sample sample) {}
    private ShadowProgram program;
    private Target world, player, receiver;
    private int receiverWidth, receiverHeight;
    private int vao, buffer;
    private final int resolution;
    private long generation;
    private boolean closed;
    private Sample current;

    private ShadowLightDepthPass(int resolution) { this.resolution = resolution; }

    public static ShadowLightDepthPass create(int resolution) {
        if (!GL.getCapabilities().OpenGL32) throw new IllegalStateException("Player shadows require the actual OpenGL 3.2 pipeline");
        if (resolution < 1 || resolution > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))
            throw new IllegalArgumentException("Shadow map resolution exceeds the active context's capacity");
        var pass = new ShadowLightDepthPass(resolution);
        try {
            try (var state = ShadowGlScope.open()) {
                state.conventionalRaster();
                pass.program = ShadowProgram.create("depth.vsh", "depth.fsh", "sourceEmission");
                pass.world = Target.create(resolution, true);
                pass.player = Target.create(resolution, false);
                pass.vao = GL30.glGenVertexArrays(); pass.buffer = GL15.glGenBuffers();
                if (pass.vao == 0 || pass.buffer == 0) throw new IllegalStateException("Cannot allocate player shadow mesh upload objects");
                GL30.glBindVertexArray(pass.vao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, pass.buffer);
                GL20.glEnableVertexAttribArray(0); GL20.glEnableVertexAttribArray(1);
                GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);
                GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 24, 12L);
                ShadowGlDiagnostics.check("allocate world/player light depth pass");
            }
        } catch (RuntimeException | Error failure) {
            try { pass.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return pass;
    }

    /** No map is published unless the whole light frustum has real loaded terrain coverage and both draws succeed. */
    public Result render(ShadowLightView view, List<ShadowTerrainCache.Section> sections,
                         PlayerShadowCapture.Caster caster, int atlasTexture, BlockPos sourceBlock) {
        return render(view, sections, caster, null, atlasTexture, sourceBlock, true);
    }

    /** Visibility selection does not need the full-resolution camera material pass. */
    public Result probe(ShadowLightView view, List<ShadowTerrainCache.Section> sections,
                        PlayerShadowCapture.Caster caster, int atlasTexture, BlockPos sourceBlock) {
        return render(view, sections, caster, null, atlasTexture, sourceBlock, false);
    }

    /** Separate contact-disc path; it cannot serve as a fallback for missing real model geometry. */
    public Result renderDisc(List<ShadowTerrainCache.Section> sections, PlayerShadowCapture.Subject subject,
                             int atlasTexture, float maxDrop) {
        if (closed) throw new IllegalStateException("Light depth pass is closed");
        var frame = TerrainSceneFrame.current();
        if (frame == null || frame.availability() != TerrainSceneFrame.Availability.READY)
            throw new IllegalStateException("Axial disc requires the current terrain frame");
        if (PlayerShadowCapture.currentSubjects().stream().noneMatch(value -> value == subject))
            throw new IllegalArgumentException("Disc subject is not from the current accepted frame");
        var origin = frame.projection();
        var disc = AxialShadowDisc.from(frame.shadowMode(), subject.pose(),
                origin.originX, origin.originY, origin.originZ, maxDrop);
        if (disc == null) {
            current = null; generation = Math.incrementExact(generation);
            return new Result(Availability.NO_AXIAL_DISC, "Free, unspecified or real-projection mode has no axial disc", null);
        }
        return render(disc.occlusionView(), sections, null, disc, atlasTexture, null, true);
    }

    private Result render(ShadowLightView view, List<ShadowTerrainCache.Section> sections,
                          PlayerShadowCapture.Caster caster, AxialShadowDisc disc, int atlasTexture, BlockPos sourceBlock,
                          boolean qualifyReceiver) {
        if (closed) throw new IllegalStateException("Light depth pass is closed");
        var frame = TerrainSceneFrame.current();
        if (frame == null || frame.availability() != TerrainSceneFrame.Availability.READY)
            throw new IllegalStateException("Light depth draw requires the current terrain frame");
        current = null; generation = Math.incrementExact(generation);
        var scene = TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN);
        if (scene == null) return new Result(Availability.NO_SCENE_DEPTH, "Current opaque scene depth required for receiver material qualification", null);
        if (scene.depthFunction != GL11.GL_LESS && scene.depthFunction != GL11.GL_LEQUAL
                && scene.depthFunction != GL11.GL_GREATER && scene.depthFunction != GL11.GL_GEQUAL)
            return new Result(Availability.UNSUPPORTED_RECEIVER_DEPTH, "Unsupported receiver depth comparison=" + scene.depthFunction, null);
        if (disc == null && (caster == null || caster.availability() != PlayerShadowCapture.Availability.READY))
            return new Result(Availability.NO_CASTER_GEOMETRY, "No verified frozen player triangles", null);
        if (disc == null && PlayerShadowCapture.currentCasters().stream().noneMatch(value -> value == caster))
            throw new IllegalStateException("Player mesh is not from the current body-render frame");
        String unavailable = coverage(view, frame.projection(), sections);
        if (unavailable != null) return new Result(Availability.INCOMPLETE_WORLD_COVERAGE, unavailable, null);
        for (var section : sections) {
            if (!ShadowTerrainCache.INSTANCE.isCurrent(section))
                return new Result(Availability.INCOMPLETE_WORLD_COVERAGE, "Expired terrain cache identity " + section.key(), null);
            if (section.translucentBlocks() != 0)
                return new Result(Availability.TRANSLUCENT_WORLD_UNAVAILABLE,
                        "No verified translucent light transmission/receiver geometry in section " + section.key(), null);
            if (!section.dynamicModels().isEmpty())
            return new Result(Availability.DYNAMIC_WORLD_MODEL_UNAVAILABLE,
                    "No frozen world TESR geometry at " + section.dynamicModels().getFirst(), null);
        }
        if (atlasTexture <= 0) throw new IllegalArgumentException("A live terrain atlas texture is required");
        if (sourceBlock != null) {
            float[] light = view.lightVector(); var origin = frame.projection();
            if (view.directional() || Math.abs(light[0] - (sourceBlock.getX() + .5 - origin.originX)) > 1E-4
                    || Math.abs(light[1] - (sourceBlock.getY() + .5 - origin.originY)) > 1E-4
                    || Math.abs(light[2] - (sourceBlock.getZ() + .5 - origin.originZ)) > 1E-4)
                throw new IllegalArgumentException("Ignored emitter block must be the actual point source");
        }
        try (var state = ShadowGlScope.open()) {
            state.conventionalRaster();
            GL11.glViewport(0, 0, resolution, resolution);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LESS); GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            program.use(); program.matrix("lightProjectionView", view.matrix());
            program.integer("materialTexture", 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world.framebuffer);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
            var origin = frame.projection();
            drawTerrain(sections, origin, atlasTexture, sourceBlock);
            ShadowGlDiagnostics.check("draw actual light-space world geometry frame=" + frame.id());
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, player.framebuffer);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            program.integer("playerGeometry", 1); program.integer("ignoreSourceBlock", 0);
            program.vector("sectionOffset", 0, 0, 0);
            for (var part : caster == null ? List.<ShadowCasterMesh.Part>of() : caster.parts()) {
                var material = part.material;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, material.texture());
                program.integer("textured", material.textured() ? 1 : 0);
                program.integer("alphaFunction", material.alphaTest() ? material.alphaFunction() : GL11.GL_ALWAYS);
                program.scalar("alphaReference", material.alphaReference());
                draw(part.vertices(), part.vertexCount());
            }
            ShadowGlDiagnostics.check("draw light-space player frame=" + frame.id() + " subject="
                    + (caster == null ? "AXIAL_DISC_NO_MODEL_SUBSTITUTE" : caster.playerId()));
            // A light-map PCF neighbour cannot establish the visible pixel's material identity.
            // Rasterize the actual receiver materials at the scene's own pixel resolution instead.
            if (qualifyReceiver) {
                if (receiver == null || receiverWidth != scene.width || receiverHeight != scene.height) {
                    Target replacement = Target.create(scene.width, scene.height, true);
                    if (receiver != null) receiver.close();
                    receiver = replacement; receiverWidth = scene.width; receiverHeight = scene.height;
                }
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, receiver.framebuffer);
                GL11.glViewport(0, 0, scene.width, scene.height);
                GL11.glClearDepth(receiverClear(scene));
                int function = scene.mapping.near() < scene.mapping.far() ? scene.depthFunction : switch (scene.depthFunction) {
                    case GL11.GL_LESS -> GL11.GL_GREATER;
                    case GL11.GL_LEQUAL -> GL11.GL_GEQUAL;
                    case GL11.GL_GREATER -> GL11.GL_LESS;
                    case GL11.GL_GEQUAL -> GL11.GL_LEQUAL;
                    default -> throw new IllegalStateException("Unsupported receiver comparison");
                };
                GL11.glDepthFunc(function);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
                float[] cameraView = new Matrix4f().set(origin.inverseProjectionView()).invert().get(new float[16]);
                program.matrix("lightProjectionView", scene.mapping.conventionalProjection(cameraView));
                drawTerrain(sections, origin, atlasTexture, null);
                ShadowGlDiagnostics.check("draw same-pixel receiver material qualification frame=" + frame.id());
            }
        }
        // Scope close checks restoration too; old or partially drawn targets cannot acquire a fresh lease.
        current = new Sample(this, generation, frame, view, receiverClear(scene), disc, qualifyReceiver);
        return new Result(Availability.RENDERED, disc == null ? "World and player light depths rendered"
                : "Axial contact receiver occlusion rendered", current);
    }

    private static float receiverClear(TerrainDepthSnapshot.Sample scene) {
        return (float) Math.max(0, Math.min(1, (scene.mapping.clear() - scene.mapping.near()) / (scene.mapping.far() - scene.mapping.near())));
    }

    private void drawTerrain(List<ShadowTerrainCache.Section> sections, TerrainSceneFrame.Projection origin,
                             int atlasTexture, BlockPos sourceBlock) {
        program.integer("playerGeometry", 0); program.integer("textured", 1);
        program.integer("ignoreSourceBlock", sourceBlock == null ? 0 : 1);
        if (sourceBlock != null) program.vector("sourceBlockMin", (float) (sourceBlock.getX() - origin.originX),
                (float) (sourceBlock.getY() - origin.originY), (float) (sourceBlock.getZ() - origin.originZ));
        else program.vector("sourceBlockMin", 0, 0, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
        for (var section : sections) {
            BlockPos base = section.key().origin();
            program.vector("sectionOffset", (float) (base.getX() - origin.originX),
                    (float) (base.getY() - origin.originY), (float) (base.getZ() - origin.originZ));
            for (var mesh : section.meshes()) {
                program.integer("alphaFunction", mesh.layer == BlockRenderLayer.SOLID ? GL11.GL_ALWAYS : GL11.GL_GREATER);
                program.scalar("alphaReference", .1F); draw(mesh.vertices(), mesh.vertexCount());
            }
        }
    }

    private void draw(FloatBuffer vertices, int count) {
        FloatBuffer upload = BufferUtils.createFloatBuffer(vertices.remaining());
        upload.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
    }

    /** Dense conservative frustum AABB: missing chunks/sections never become empty occluders. */
    private static String coverage(ShadowLightView view, TerrainSceneFrame.Projection origin,
                                    List<ShadowTerrainCache.Section> sections) {
        var required = ShadowLightCoverage.required(view, origin);
        if (required.unavailable() != null) return required.unavailable();
        var byKey = new HashMap<ShadowTerrainCache.Key, ShadowTerrainCache.Section>();
        for (var section : sections) if (byKey.put(section.key(), section) != null)
            throw new IllegalArgumentException("Duplicate terrain section in light coverage: " + section.key());
        for (var key : required.keys()) {
            var section = byKey.get(key);
            if (section == null || section.availability() != ShadowTerrainCache.Availability.READY)
                return "Light path has missing/unavailable terrain section " + key;
        }
        return null;
    }

    public static final class Sample {
        private final ShadowLightDepthPass owner;
        private final long generation;
        private final TerrainSceneFrame.WorldScope frame;
        public final ShadowLightView view;
        private final float receiverClear;
        private final AxialShadowDisc disc;
        private final boolean qualified;
        private Sample(ShadowLightDepthPass owner, long generation, TerrainSceneFrame.WorldScope frame, ShadowLightView view,
                       float receiverClear, AxialShadowDisc disc, boolean qualified) {
            this.owner = owner; this.generation = generation; this.frame = frame; this.view = view;
            this.receiverClear = receiverClear;
            this.disc = disc;
            this.qualified = qualified;
        }
        private void live() {
            if (owner.closed || owner.current != this || owner.generation != generation || TerrainSceneFrame.current() != frame
                    || frame.availability() != TerrainSceneFrame.Availability.READY)
                throw new IllegalStateException("Expired world/player light-depth lease");
        }
        public int worldDepth() { live(); return owner.world.depth; }
        public int playerDepth() { live(); return owner.player.depth; }
        public int emission() { live(); return owner.world.emission; }
        public int resolution() { live(); return owner.resolution; }
        private void requireQualified() {
            live();
            if (!qualified) throw new IllegalStateException("Visibility-only light sample cannot qualify a camera receiver");
        }
        public int receiverDepth() { requireQualified(); return owner.receiver.depth; }
        public int receiverEmission() { requireQualified(); return owner.receiver.emission; }
        public int receiverWidth() { requireQualified(); return owner.receiverWidth; }
        public int receiverHeight() { requireQualified(); return owner.receiverHeight; }
        public float receiverClearDepth() { live(); return receiverClear; }
        public AxialShadowDisc axialDisc() { live(); return disc; }
    }

    @Override public void close() {
        if (closed) return;
        closed = true; current = null;
        if (world != null) world.close(); if (player != null) player.close();
        if (receiver != null) receiver.close();
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        if (program != null) program.close();
    }

    private static final class Target implements AutoCloseable {
        private int framebuffer, depth, emission;
        static Target create(int resolution, boolean withEmission) {
            return create(resolution, resolution, withEmission);
        }
        static Target create(int width, int height, boolean withEmission) {
            Target target = new Target(); boolean complete = false;
            try {
                target.framebuffer = GL30.glGenFramebuffers(); target.depth = texture(width, height, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT);
                if (target.framebuffer == 0) throw new IllegalStateException("Cannot allocate light depth framebuffer");
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, target.depth, 0);
                if (withEmission) {
                    target.emission = texture(width, height, GL30.GL_R8, GL11.GL_RED);
                    GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target.emission, 0);
                }
                GL11.glDrawBuffer(withEmission ? GL30.GL_COLOR_ATTACHMENT0 : GL11.GL_NONE);
                GL11.glReadBuffer(GL11.GL_NONE);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("World/player light framebuffer incomplete");
                ShadowGlDiagnostics.check("allocate light depth framebuffer size=" + width + "x" + height + " emission=" + withEmission);
                complete = true; return target;
            } finally { if (!complete) target.close(); }
        }
        private static int texture(int width, int height, int format, int external) {
            int texture = GL11.glGenTextures();
            if (texture == 0) throw new IllegalStateException("Cannot allocate light depth texture");
            boolean complete = false;
            try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, external, GL11.GL_FLOAT, (ByteBuffer) null);
            complete = true;
            return texture;
            } finally { if (!complete) GL11.glDeleteTextures(texture); }
        }
        @Override public void close() {
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (depth != 0) GL11.glDeleteTextures(depth);
            if (emission != 0) GL11.glDeleteTextures(emission);
        }
    }
}

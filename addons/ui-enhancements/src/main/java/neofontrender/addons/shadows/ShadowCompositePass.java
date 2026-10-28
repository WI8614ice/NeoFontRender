package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Unions bounded player shadow masks, then darkens the scene once; never reads a live scene colour attachment. */
public final class ShadowCompositePass implements AutoCloseable {
    public record Region(float x, float y, float z, float radius) {
        public Region {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(radius) || radius <= 0)
                throw new IllegalArgumentException("Finite positive receiver region required");
        }
    }
    private ShadowProgram maskProgram, applyProgram;
    private int vao;
    private MaskTarget target;
    private Frame current;
    private boolean closed;

    public static ShadowCompositePass create() {
        if (!GL.getCapabilities().OpenGL32) throw new IllegalStateException("Player shadow composition requires OpenGL 3.2");
        var pass = new ShadowCompositePass();
        try {
            try (var state = ShadowGlScope.open()) {
                state.conventionalRaster();
                pass.maskProgram = ShadowProgram.create("screen.vsh", "mask.fsh", "shadowMask");
                pass.applyProgram = ShadowProgram.create("screen.vsh", "apply.fsh", "colour");
                pass.vao = GL30.glGenVertexArrays();
                if (pass.vao == 0) throw new IllegalStateException("Cannot allocate shadow compositor VAO");
                ShadowGlDiagnostics.check("allocate shadow compositor");
            }
        } catch (RuntimeException | Error failure) {
            try { pass.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return pass;
    }

    public Frame begin(TerrainDepthSnapshot.Sample terrain, TerrainDepthSnapshot.Sample foreground) {
        if (closed) throw new IllegalStateException("Shadow compositor closed");
        if (terrain == null || foreground == null
                || TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.OPAQUE_TERRAIN) != terrain
                || TerrainDepthSnapshot.current(TerrainDepthSnapshot.Layer.FOREGROUND) != foreground
                || terrain.projection() != foreground.projection()
                || terrain.width != foreground.width || terrain.height != foreground.height
                || terrain.viewportX != foreground.viewportX || terrain.viewportY != foreground.viewportY
                || !terrain.mapping.equals(foreground.mapping))
            throw new IllegalArgumentException("Same-frame terrain and foreground depth convention required");
        terrain.texture(); foreground.texture();
        current = null;
        int scene = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try (var state = ShadowGlScope.open()) {
            state.conventionalRaster();
            if (target == null || target.width != terrain.width || target.height != terrain.height) {
                MaskTarget replacement = MaskTarget.create(terrain.width, terrain.height);
                if (target != null) target.close();
                target = replacement;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
            GL11.glViewport(0, 0, target.width, target.height);
            GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            ShadowGlDiagnostics.check("clear union shadow mask");
        }
        current = new Frame(this, TerrainSceneFrame.current(), terrain, foreground, scene);
        return current;
    }

    public final class Frame {
        private final ShadowCompositePass owner;
        private final TerrainSceneFrame.WorldScope frame;
        private final TerrainDepthSnapshot.Sample terrain, foreground;
        private final int scene;
        private boolean any, applied, failed;
        private Frame(ShadowCompositePass owner, TerrainSceneFrame.WorldScope frame, TerrainDepthSnapshot.Sample terrain,
                      TerrainDepthSnapshot.Sample foreground, int scene) {
            this.owner = owner; this.frame = frame; this.terrain = terrain; this.foreground = foreground; this.scene = scene;
        }
        private void live() {
            if (owner.closed || owner.current != this || TerrainSceneFrame.current() != frame || applied || failed)
                throw new IllegalStateException("Expired union shadow frame");
            terrain.texture(); foreground.texture();
        }

        /** Same real light view supplies both the maps and surface-facing term; a camera cannot steer the light. */
        public void accumulate(ShadowLightDepthPass.Sample light, Region region, float strength) {
            live();
            if (!Float.isFinite(strength) || strength < 0 || strength > 1) throw new IllegalArgumentException("Unit shadow strength required");
            int worldDepth = light.worldDepth(), playerDepth = light.playerDepth(), emission = light.emission();
            if (light.receiverWidth() != terrain.width || light.receiverHeight() != terrain.height)
                throw new IllegalArgumentException("Receiver qualification resolution differs from scene pixels");
            int[] rect = rectangle(terrain, region);
            if (rect[2] == 0 || rect[3] == 0 || strength == 0) return;
            failed = true;
            try (var state = ShadowGlScope.open()) {
                state.conventionalRaster();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
                GL30.glBindVertexArray(vao);
                GL11.glViewport(0, 0, target.width, target.height);
                GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(rect[0], rect[1], rect[2], rect[3]);
                GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_BLEND); GL14.glBlendEquation(GL14.GL_MAX);
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                maskProgram.use();
                bind(maskProgram, "sceneDepth", 0, terrain.texture());
                bind(maskProgram, "foregroundDepth", 1, foreground.texture());
                bind(maskProgram, "worldDepth", 2, worldDepth);
                bind(maskProgram, "playerDepth", 3, playerDepth);
                bind(maskProgram, "sourceEmission", 4, emission);
                bind(maskProgram, "receiverDepth", 5, light.receiverDepth());
                bind(maskProgram, "receiverEmission", 6, light.receiverEmission());
                maskProgram.scalar("receiverClearDepth", light.receiverClearDepth());
                maskProgram.matrix("inverseProjectionView", terrain.projection().inverseProjectionView());
                maskProgram.matrix("lightProjectionView", light.view.matrix());
                maskProgram.matrix("inverseLightProjectionView", light.view.inverseMatrix());
                maskProgram.vector("windowMapping", (float) terrain.mapping.near(), (float) terrain.mapping.far(), (float) terrain.mapping.clear());
                maskProgram.integer("zeroToOne", terrain.mapping.zeroToOne() ? 1 : 0);
                maskProgram.integer("upperLeft", terrain.mapping.upperLeft() ? 1 : 0);
                maskProgram.vector("screenSize", target.width, target.height, 0);
                Vector4f camera = new Matrix4f().set(terrain.projection().inverseModelView()).transform(new Vector4f(0, 0, 0, 1));
                maskProgram.vector("cameraPosition", camera.x / camera.w, camera.y / camera.w, camera.z / camera.w);
                float[] vector = light.view.lightVector();
                maskProgram.vector("lightVector", vector[0], vector[1], vector[2]);
                maskProgram.integer("directional", light.view.directional() ? 1 : 0);
                maskProgram.vector("receiverCenter", region.x, region.y, region.z);
                maskProgram.scalar("receiverRadius", region.radius);
                maskProgram.scalar("strength", strength);
                maskProgram.scalar("mapResolution", light.resolution());
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                ShadowGlDiagnostics.check("accumulate bounded real player shadow frame=" + frame.id());
            }
            failed = false; any = true;
        }

        /** MAX union prevents overlapping players/lights from repeatedly darkening the same scene pixel. */
        public void apply() {
            live();
            if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != scene)
                throw new IllegalStateException("Scene framebuffer changed before shadow composition");
            if (!any) { applied = true; return; }
            boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
            boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            var scissorRect = BufferUtils.createIntBuffer(4); GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorRect);
            var colourMask = BufferUtils.createByteBuffer(4); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colourMask);
            applied = true; // A draw/restore failure must not allow applying the same mask a second time.
            try (var state = ShadowGlScope.open()) {
                state.conventionalRaster();
                if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
                if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
                GL20.glStencilMaskSeparate(GL11.GL_FRONT, 0);
                GL20.glStencilMaskSeparate(GL11.GL_BACK, 0);
                if (scissor) { GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(scissorRect.get(0), scissorRect.get(1), scissorRect.get(2), scissorRect.get(3)); }
                GL11.glColorMask(colourMask.get(0) != 0, colourMask.get(1) != 0, colourMask.get(2) != 0, false);
                GL30.glBindVertexArray(vao);
                GL11.glViewport(terrain.viewportX, terrain.viewportY, terrain.width, terrain.height);
                GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_BLEND); GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                applyProgram.use(); bind(applyProgram, "unionMask", 0, target.texture);
                applyProgram.vector("screenSize", target.width, target.height, 0);
                applyProgram.vector("viewportOrigin", terrain.viewportX, terrain.viewportY, 0);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                ShadowGlDiagnostics.check("apply single union player shadow frame=" + frame.id());
            }
        }
    }

    private static void bind(ShadowProgram program, String uniform, int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); program.integer(uniform, unit);
    }

    private static int[] rectangle(TerrainDepthSnapshot.Sample sample, Region region) {
        Matrix4f pv = new Matrix4f().set(sample.projection().inverseProjectionView()).invert();
        float minX = 1, minY = 1, maxX = -1, maxY = -1;
        for (int i = 0; i < 8; i++) {
            Vector4f p = pv.transform(new Vector4f(region.x + ((i & 1) == 0 ? -region.radius : region.radius),
                    region.y + ((i & 2) == 0 ? -region.radius : region.radius),
                    region.z + ((i & 4) == 0 ? -region.radius : region.radius), 1));
            if (p.w <= 0) return new int[] {0, 0, sample.width, sample.height}; // region intersects/contains the camera plane
            float x = p.x / p.w, y = (sample.mapping.upperLeft() ? -p.y : p.y) / p.w;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        int x0 = Math.max(0, Math.min(sample.width, (int) Math.floor((minX + 1) * .5 * sample.width)));
        int x1 = Math.max(0, Math.min(sample.width, (int) Math.ceil((maxX + 1) * .5 * sample.width)));
        int y0 = Math.max(0, Math.min(sample.height, (int) Math.floor((minY + 1) * .5 * sample.height)));
        int y1 = Math.max(0, Math.min(sample.height, (int) Math.ceil((maxY + 1) * .5 * sample.height)));
        return new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
    }

    @Override public void close() {
        if (closed) return;
        closed = true; current = null;
        if (target != null) target.close();
        if (maskProgram != null) maskProgram.close(); if (applyProgram != null) applyProgram.close();
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
    }

    private static final class MaskTarget implements AutoCloseable {
        private final int width, height;
        private int framebuffer, texture;
        private MaskTarget(int width, int height) { this.width = width; this.height = height; }
        static MaskTarget create(int width, int height) {
            var target = new MaskTarget(width, height); boolean complete = false;
            try {
                target.texture = GL11.glGenTextures(); target.framebuffer = GL30.glGenFramebuffers();
                if (target.texture == 0 || target.framebuffer == 0) throw new IllegalStateException("Cannot allocate union shadow target");
                GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, width, height, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target.texture, 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL11.GL_NONE);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Union shadow framebuffer incomplete");
                ShadowGlDiagnostics.check("allocate union shadow target " + width + "x" + height);
                complete = true; return target;
            } finally { if (!complete) target.close(); }
        }
        @Override public void close() {
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (texture != 0) GL11.glDeleteTextures(texture);
        }
    }
}

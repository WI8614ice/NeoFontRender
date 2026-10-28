package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.DoubleBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Owns a resolved read-only copy of opaque terrain depth, never replaces the renderer's attachment. */
public final class TerrainDepthSnapshot {
    private static Target target;
    private static Object world;
    private static Sample sample;

    private TerrainDepthSnapshot() {}

    /** Must run at the frame's post-cutout/pre-entity stage on the render thread. */
    public static void capture() {
        TerrainSceneFrame.WorldScope frame = TerrainSceneFrame.current();
        if (frame == null || frame.availability() != TerrainSceneFrame.Availability.READY) return;
        sample = null;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null) return;
        if (world != minecraft.world) {
            release();
            world = minecraft.world;
        }
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int x = viewport.get(0), y = viewport.get(1), width = viewport.get(2), height = viewport.get(3);
        if (width <= 0 || height <= 0) throw new IllegalStateException("Empty opaque terrain viewport");
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        DoubleBuffer range = BufferUtils.createDoubleBuffer(2);
        GL11.glGetDouble(GL11.GL_DEPTH_RANGE, range);
        double rangeNear = range.get(0), rangeFar = range.get(1);
        if (!Double.isFinite(rangeNear) || !Double.isFinite(rangeFar) || rangeNear == rangeFar)
            throw new IllegalStateException("Noninvertible opaque terrain depth range");
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            int format = sourceFormat(drawFramebuffer);
            if (target == null || target.width != width || target.height != height || target.format != format) {
                Target replacement = Target.create(width, height, format);
                if (target != null) target.close();
                target = replacement;
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.framebuffer);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            // Same format and dimensions permit depth-only MSAA resolve without sampling a live attachment.
            GL30.glBlitFramebuffer(x, y, x + width, y + height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            sample = new Sample(frame, target.texture, width, height, x, y, rangeNear, rangeFar);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager.bindTexture(texture);
            GlStateManager.setActiveTexture(activeTexture);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private static int sourceFormat(int framebuffer) {
        int attachment = framebuffer == 0 ? GL11.GL_DEPTH : GL30.GL_DEPTH_ATTACHMENT;
        int objectType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (objectType == GL30.GL_RENDERBUFFER) {
            int object = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            try {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, object);
                return GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            } finally {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
            }
        }
        // Named texture attachments can be arrays/MSAA/rectangle targets. Do not guess their target or
        // internal storage from depth/stencil bit counts: depth-only blits require identical formats.
        if (framebuffer != 0)
            throw new IllegalStateException("Opaque terrain depth requires an exact texture-attachment provider; objectType=" + objectType);
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        return matchingFormat(GL11.glGetInteger(GL11.GL_DEPTH_BITS), GL11.glGetInteger(GL11.GL_STENCIL_BITS), type);
    }

    private static int matchingFormat(int depth, int stencil, int type) {
        if (stencil > 0 && depth == 24) return GL30.GL_DEPTH24_STENCIL8;
        if (stencil > 0 && depth == 32 && type == GL11.GL_FLOAT) return GL30.GL_DEPTH32F_STENCIL8;
        if (stencil == 0 && depth == 16) return GL14.GL_DEPTH_COMPONENT16;
        if (stencil == 0 && depth == 24) return GL14.GL_DEPTH_COMPONENT24;
        if (stencil == 0 && depth == 32)
            return type == GL11.GL_FLOAT ? GL30.GL_DEPTH_COMPONENT32F : GL14.GL_DEPTH_COMPONENT32;
        throw new IllegalStateException("Unsupported opaque depth storage: depth=" + depth
                + " stencil=" + stencil + " componentType=" + type);
    }

    /** No previous frame is returned when terrain capture or the current pass is unavailable. */
    public static Sample current() {
        if (sample == null || TerrainSceneFrame.current() != sample.frame) return null;
        sample.requireLive();
        return sample;
    }

    /** Called on world unload/resource reload on the render thread; GL object ownership stays here. */
    public static void release() {
        sample = null;
        world = null;
        if (target != null) {
            target.close();
            target = null;
        }
    }

    public static final class Sample {
        private final TerrainSceneFrame.WorldScope frame;
        private final int texture;
        public final int width, height, viewportX, viewportY;
        public final double rangeNear, rangeFar;
        private Sample(TerrainSceneFrame.WorldScope frame, int texture, int width, int height, int x, int y,
                       double rangeNear, double rangeFar) {
            this.frame = frame;
            this.texture = texture;
            this.width = width; this.height = height; viewportX = x; viewportY = y;
            this.rangeNear = rangeNear; this.rangeFar = rangeFar;
        }
        public int texture() { requireLive(); return texture; }
        public TerrainSceneFrame.Projection projection() { requireLive(); return frame.projection(); }
        private void requireLive() {
            if (TerrainSceneFrame.current() != frame || frame.availability() != TerrainSceneFrame.Availability.READY)
                throw new IllegalStateException("Expired opaque terrain depth lease");
        }
    }

    private static final class Target implements AutoCloseable {
        private final int width, height, format, framebuffer, texture;
        private Target(int width, int height, int format, int framebuffer, int texture) {
            this.width = width; this.height = height; this.format = format;
            this.framebuffer = framebuffer; this.texture = texture;
        }
        private static Target create(int width, int height, int format) {
            int texture = GL11.glGenTextures();
            int framebuffer = GL30.glGenFramebuffers();
            boolean complete = false;
            try {
                GlStateManager.bindTexture(texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                boolean packed = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
                int external = packed ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
                int type = format == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8
                        : format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, external, type, (ByteBuffer) null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
                        packed ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
                        GL11.GL_TEXTURE_2D, texture, 0);
                GL11.glDrawBuffer(GL11.GL_NONE);
                GL11.glReadBuffer(GL11.GL_NONE);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Opaque depth snapshot FBO incomplete: " + status);
                complete = true;
                return new Target(width, height, format, framebuffer, texture);
            } finally {
                if (!complete) {
                    GL30.glDeleteFramebuffers(framebuffer);
                    GlStateManager.deleteTexture(texture);
                }
            }
        }
        @Override public void close() {
            GL30.glDeleteFramebuffers(framebuffer);
            GlStateManager.deleteTexture(texture);
        }
    }
}

package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.DoubleBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL45;

/** Owns a resolved read-only copy of opaque terrain depth, never replaces the renderer's attachment. */
public final class TerrainDepthSnapshot {
    public enum Availability { CAPTURED, NO_READY_FRAME, NO_WORLD, UNSUPPORTED_DEPTH_STORAGE }
    public enum Layer { OPAQUE_TERRAIN, FOREGROUND }
    public record Result(Availability availability, String detail, Sample sample) {}
    private record Storage(int format, String unavailable) {}
    private static final Map<Layer, Slot> SLOTS = new EnumMap<>(Layer.class);
    static { for (Layer layer : Layer.values()) SLOTS.put(layer, new Slot()); }
    private static Object world;

    private TerrainDepthSnapshot() {}

    /** Must run at the frame's post-cutout/pre-entity stage on the render thread. */
    public static Result capture(Layer layer) {
        Slot slot = SLOTS.get(Objects.requireNonNull(layer, "layer"));
        TerrainSceneFrame.WorldScope frame = TerrainSceneFrame.current();
        if (frame == null || frame.availability() != TerrainSceneFrame.Availability.READY)
            return new Result(Availability.NO_READY_FRAME, "No current opaque terrain frame", null);
        slot.sample = null;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null) return new Result(Availability.NO_WORLD, "No client world", null);
        if (world != minecraft.world) {
            release();
            world = minecraft.world;
        }
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int x = viewport.get(0), y = viewport.get(1), width = viewport.get(2), height = viewport.get(3);
        String context = "frame=" + frame.id() + " layer=" + layer + " source=" + drawFramebuffer
                + " sourceRect=[" + x + "," + y + "," + width + "," + height + "]";
        ShadowGlDiagnostics.check("before depth capture (pre-existing renderer errors); " + context);
        if (width <= 0 || height <= 0) throw new IllegalStateException("Empty opaque terrain viewport");
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        DoubleBuffer range = BufferUtils.createDoubleBuffer(2);
        GL11.glGetDouble(GL11.GL_DEPTH_RANGE, range);
        double rangeNear = range.get(0), rangeFar = range.get(1);
        double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        var capabilities = GL.getCapabilities();
        boolean clipControl = capabilities.OpenGL45 || capabilities.GL_ARB_clip_control;
        boolean zeroToOne = clipControl && GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE) == GL45.GL_ZERO_TO_ONE;
        boolean upperLeft = clipControl && GL11.glGetInteger(GL45.GL_CLIP_ORIGIN) == GL20.GL_UPPER_LEFT;
        if (!Double.isFinite(rangeNear) || !Double.isFinite(rangeFar) || rangeNear == rangeFar)
            throw new IllegalStateException("Noninvertible opaque terrain depth range");
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        Sample captured = null;
        Result unavailable = null;
        Throwable primary = null;
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            int sourceStatus = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            ShadowGlDiagnostics.check("query source completeness; " + context);
            if (sourceStatus != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Opaque depth source incomplete: " + sourceStatus + "; " + context);
            Storage storage = sourceFormat(drawFramebuffer);
            ShadowGlDiagnostics.check("query source depth storage; " + context);
            if (storage.unavailable != null) {
                unavailable = new Result(Availability.UNSUPPORTED_DEPTH_STORAGE, storage.unavailable, null);
            } else {
                int format = storage.format;
                if (slot.target == null || slot.target.width != width || slot.target.height != height || slot.target.format != format) {
                    Target replacement = Target.create(width, height, format, context);
                    if (slot.target != null) slot.target.close();
                    slot.target = replacement;
                }
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, slot.target.framebuffer);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                String copy = context + " target=" + slot.target.framebuffer + " format=" + format
                        + " targetRect=[0,0," + width + "," + height + "]";
                ShadowGlDiagnostics.check("before depth copy; " + copy);
                // Same format and dimensions permit depth-only MSAA resolve without sampling a live attachment.
                GL30.glBlitFramebuffer(x, y, x + width, y + height, 0, 0, width, height,
                        GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                ShadowGlDiagnostics.check("depth copy; " + copy);
                captured = new Sample(slot, frame, slot.target.texture, width, height, x, y, rangeNear, rangeFar,
                        clearDepth, zeroToOne, upperLeft);
            }
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                GlStateManager.bindTexture(texture);
                GlStateManager.setActiveTexture(activeTexture);
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                else GL11.glDisable(GL11.GL_SCISSOR_TEST);
                ShadowGlDiagnostics.check("restore after depth capture; " + context);
            } catch (RuntimeException | Error restoreFailure) {
                if (primary != null) primary.addSuppressed(restoreFailure);
                else throw restoreFailure;
            }
        }
        // Publish only after both this copy and restoration succeed. Reused GPU contents have no lease on failure.
        if (unavailable != null) return unavailable;
        slot.sample = captured;
        return new Result(Availability.CAPTURED, "Resolved " + layer + " depth", captured);
    }

    private static Storage sourceFormat(int framebuffer) {
        if (framebuffer == 0)
            return new Storage(0, "Default framebuffer does not expose an exact reusable depth internal format");
        int attachment = GL30.GL_DEPTH_ATTACHMENT;
        int objectType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (objectType == GL30.GL_RENDERBUFFER) {
            int object = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            try {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, object);
                return storage(GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT));
            } finally {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
            }
        }
        if (objectType == GL11.GL_TEXTURE) {
            var capabilities = GL.getCapabilities();
            if (!capabilities.OpenGL45 && !capabilities.GL_ARB_direct_state_access)
                return new Storage(0, "Exact texture depth format requires OpenGL 4.5 or ARB_direct_state_access");
            int texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            // DSA works for the attachment's real 2D/array/MSAA target without rebinding or guessing it.
            return storage(GL45.glGetTextureLevelParameteri(texture, level, GL11.GL_TEXTURE_INTERNAL_FORMAT));
        }
        return new Storage(0, "No reusable named terrain depth attachment; objectType=" + objectType);
    }

    private static Storage storage(int format) {
        return switch (format) {
            case GL14.GL_DEPTH_COMPONENT16, GL14.GL_DEPTH_COMPONENT24, GL14.GL_DEPTH_COMPONENT32,
                    GL30.GL_DEPTH_COMPONENT32F, GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH32F_STENCIL8 -> new Storage(format, null);
            default -> new Storage(0, "Unsupported exact terrain depth internal format=" + format);
        };
    }

    /** No previous frame is returned when terrain capture or the current pass is unavailable. */
    public static Sample current(Layer layer) {
        Sample sample = SLOTS.get(Objects.requireNonNull(layer, "layer")).sample;
        if (sample == null || TerrainSceneFrame.current() != sample.frame) return null;
        sample.requireLive();
        return sample;
    }

    /** Called on world unload/resource reload on the render thread; GL object ownership stays here. */
    public static void release() {
        world = null;
        for (Slot slot : SLOTS.values()) {
            slot.sample = null;
            if (slot.target != null) {
                slot.target.close();
                slot.target = null;
            }
        }
    }

    public static final class Sample {
        private final Slot slot;
        private final TerrainSceneFrame.WorldScope frame;
        private final int texture;
        public final int width, height, viewportX, viewportY;
        public final WindowDepthMapping mapping;
        private Sample(Slot slot, TerrainSceneFrame.WorldScope frame, int texture, int width, int height, int x, int y,
                       double rangeNear, double rangeFar, double clearDepth, boolean zeroToOne, boolean upperLeft) {
            this.frame = frame;
            this.slot = slot;
            this.texture = texture;
            this.width = width; this.height = height; viewportX = x; viewportY = y;
            mapping = new WindowDepthMapping(rangeNear, rangeFar, clearDepth, zeroToOne, upperLeft);
        }
        public int texture() { requireLive(); return texture; }
        public TerrainSceneFrame.Projection projection() { requireLive(); return frame.projection(); }
        public float[] relativePoint(float u, float v, float depth) {
            requireLive();
            var clip = mapping.toClip(u, v, depth);
            return frame.projection().relativeClipPoint(clip.x(), clip.y(), clip.z());
        }
        private void requireLive() {
            if (slot.sample != this || TerrainSceneFrame.current() != frame
                    || frame.availability() != TerrainSceneFrame.Availability.READY)
                throw new IllegalStateException("Expired opaque terrain depth lease");
        }
    }

    private static final class Target implements AutoCloseable {
        private final int width, height, format, framebuffer, texture;
        private Target(int width, int height, int format, int framebuffer, int texture) {
            this.width = width; this.height = height; this.format = format;
            this.framebuffer = framebuffer; this.texture = texture;
        }
        private static Target create(int width, int height, int format, String context) {
            int texture = GL11.glGenTextures();
            if (texture == 0) throw new IllegalStateException("glGenTextures returned zero for opaque depth snapshot");
            int framebuffer = GL30.glGenFramebuffers();
            if (framebuffer == 0) {
                GlStateManager.deleteTexture(texture);
                throw new IllegalStateException("glGenFramebuffers returned zero for opaque depth snapshot");
            }
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
                ShadowGlDiagnostics.check("allocate depth target=" + framebuffer + " format=" + format + "; " + context);
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

    private static final class Slot {
        private Target target;
        private Sample sample;
    }
}

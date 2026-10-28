package neofontrender.addons.shadows;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

/** Raw GL only inside the scope: Minecraft's cached state stays equal to the restored driver state. */
final class ShadowGlScope implements AutoCloseable {
    private final int program, vao, arrayBuffer, unpackBuffer, renderbuffer, readFramebuffer, drawFramebuffer, textureUnit;
    private final boolean clipControl, rasterDiscard, sampleMask;
    private final int clipOrigin, clipDepth;
    private final boolean samplerObjects;
    private final int[] samplers = new int[7];
    private final IntBuffer drawBuffers;
    private final int readBuffer;
    private boolean closed;

    private ShadowGlScope() {
        ShadowGlDiagnostics.check("before player shadow pass (pre-existing renderer errors)");
        int stackDepth = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
        if (stackDepth >= GL11.glGetInteger(GL11.GL_MAX_ATTRIB_STACK_DEPTH))
            throw new IllegalStateException("No attribute stack slot for player shadow state scope");
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        textureUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        drawBuffers = BufferUtils.createIntBuffer(GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS));
        for (int i = 0; i < drawBuffers.capacity(); i++) drawBuffers.put(i, GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i));
        rasterDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        sampleMask = GL11.glIsEnabled(GL32.GL_SAMPLE_MASK);
        clipControl = GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_clip_control;
        clipOrigin = clipControl ? GL11.glGetInteger(GL45.GL_CLIP_ORIGIN) : GL20.GL_LOWER_LEFT;
        clipDepth = clipControl ? GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE) : GL45.GL_NEGATIVE_ONE_TO_ONE;
        samplerObjects = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        if (samplerObjects) for (int i = 0; i < samplers.length; i++)
            samplers[i] = GL30.glGetInteger(GL33.GL_SAMPLER_BINDING, i);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try { ShadowGlDiagnostics.check("capture player shadow GL state"); }
        catch (RuntimeException | Error failure) {
            if (GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH) > stackDepth) GL11.glPopAttrib();
            throw failure;
        }
    }

    static ShadowGlScope open() { return new ShadowGlScope(); }

    /** Own targets use a conventional depth convention; the original convention is restored on close. */
    void conventionalRaster() {
        if (clipControl) GL45.glClipControl(GL20.GL_LOWER_LEFT, GL45.GL_NEGATIVE_ONE_TO_ONE);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL11.glDisable(GL32.GL_SAMPLE_MASK);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDisable(GL11.GL_DITHER);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL11.GL_POLYGON_STIPPLE);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
        if (samplerObjects) for (int unit = 0; unit < samplers.length; unit++) GL33.glBindSampler(unit, 0);
        for (int plane = 0; plane < GL11.glGetInteger(GL30.GL_MAX_CLIP_DISTANCES); plane++)
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + plane);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glDepthRange(0, 1);
        GL11.glClearDepth(1);
        GL11.glColorMask(true, true, true, true);
    }

    @Override public void close() {
        if (closed) throw new IllegalStateException("Player shadow GL scope closed twice");
        closed = true;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        GL20.glUseProgram(program);
        if (clipControl) GL45.glClipControl(clipOrigin, clipDepth);
        GL11.glPopAttrib();
        if (drawFramebuffer == 0) GL11.glDrawBuffer(drawBuffers.get(0));
        else GL20.glDrawBuffers(drawBuffers);
        GL11.glReadBuffer(readBuffer);
        if (samplerObjects) for (int unit = 0; unit < samplers.length; unit++) GL33.glBindSampler(unit, samplers[unit]);
        GL13.glActiveTexture(textureUnit);
        if (rasterDiscard) GL11.glEnable(GL30.GL_RASTERIZER_DISCARD); else GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        if (sampleMask) GL11.glEnable(GL32.GL_SAMPLE_MASK); else GL11.glDisable(GL32.GL_SAMPLE_MASK);
        ShadowGlDiagnostics.check("restore player shadow GL scope");
    }
}

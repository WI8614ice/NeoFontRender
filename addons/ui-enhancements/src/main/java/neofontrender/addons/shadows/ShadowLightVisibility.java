package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

/** Evaluates actual current light-map visibility, never interprets block brightness as a clear light path. */
final class ShadowLightVisibility implements AutoCloseable {
    private ShadowProgram program;
    private int vao, query, framebuffer, texture;
    private final int resolution;
    private boolean closed;

    private ShadowLightVisibility(int resolution) { this.resolution = resolution; }

    static ShadowLightVisibility create(int resolution) {
        var pass = new ShadowLightVisibility(resolution);
        try {
            try (var state = ShadowGlScope.open()) {
                state.conventionalRaster();
                pass.program = ShadowProgram.create("screen.vsh", "visibility.fsh", "visibilityResult");
                pass.vao = GL30.glGenVertexArrays(); pass.query = GL15.glGenQueries();
                pass.framebuffer = GL30.glGenFramebuffers(); pass.texture = GL11.glGenTextures();
                if (pass.vao == 0 || pass.query == 0 || pass.framebuffer == 0 || pass.texture == 0)
                    throw new IllegalStateException("Cannot allocate light visibility query target");
                GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, pass.texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, resolution, resolution, 0,
                        GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pass.framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, pass.texture, 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL11.GL_NONE);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Light visibility framebuffer incomplete");
                ShadowGlDiagnostics.check("allocate actual light visibility query");
            }
            return pass;
        } catch (RuntimeException | Error failure) {
            try { pass.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    ShadowMainLight.Visibility evaluate(ShadowLightDepthPass.Sample sample) {
        if (closed || sample.axialDisc() != null || sample.resolution() != resolution)
            throw new IllegalArgumentException("Current real-player light maps required");
        // Do not end or nest another renderer's occlusion query. No inherited query is ours to consume.
        var caps = GL.getCapabilities();
        if (GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0
                || (caps.OpenGL33 || caps.GL_ARB_occlusion_query2)
                && GL15.glGetQueryi(GL33.GL_ANY_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0
                || (caps.OpenGL43 || caps.GL_ARB_ES3_compatibility)
                && GL15.glGetQueryi(GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE, GL15.GL_CURRENT_QUERY) != 0)
            return ShadowMainLight.Visibility.UNAVAILABLE;
        int samples;
        try (var state = ShadowGlScope.open()) {
            state.conventionalRaster();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glBindVertexArray(vao);
            GL11.glViewport(0, 0, resolution, resolution);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND); GL11.glColorMask(false, false, false, false);
            program.use();
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, sample.worldDepth());
            program.integer("worldDepth", 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, sample.playerDepth());
            program.integer("playerDepth", 1);
            program.vector("mapSize", resolution, resolution, 0);
            program.matrix("inverseLightProjectionView", sample.view.inverseMatrix());
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
            try { GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); }
            finally { GL15.glEndQuery(GL15.GL_SAMPLES_PASSED); }
            // Deliberately obtain this frame's proof. Delayed results from another animated pose/world
            // cannot authorize a current main light. This bounded query may stall; GPU cost is not yet measured.
            samples = GL15.glGetQueryObjectui(query, GL15.GL_QUERY_RESULT);
            ShadowGlDiagnostics.check("evaluate actual current light-to-player visibility");
        }
        return samples == 0 ? ShadowMainLight.Visibility.OCCLUDED : ShadowMainLight.Visibility.VISIBLE;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (query != 0) GL15.glDeleteQueries(query);
        if (program != null) program.close();
    }
}

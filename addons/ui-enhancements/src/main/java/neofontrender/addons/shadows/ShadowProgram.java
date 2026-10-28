package neofontrender.addons.shadows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Owns one linked program; a failed compile/link never publishes a usable handle. */
final class ShadowProgram implements AutoCloseable {
    final int handle;
    private final Map<String, Integer> uniforms = new HashMap<>();

    private ShadowProgram(int handle) { this.handle = handle; }

    static ShadowProgram create(String vertex, String fragment, String output) {
        int vs = 0, fs = 0, program = 0;
        boolean complete = false;
        try {
            vs = compile(GL20.GL_VERTEX_SHADER, vertex);
            fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            program = GL20.glCreateProgram();
            if (program == 0) throw new IllegalStateException("Cannot allocate shadow program: " + vertex + "/" + fragment);
            GL20.glAttachShader(program, vs); GL20.glAttachShader(program, fs);
            GL20.glBindAttribLocation(program, 0, "position");
            GL20.glBindAttribLocation(program, 1, "auxiliary");
            GL20.glBindAttribLocation(program, 2, "vertexAlpha");
            GL30.glBindFragDataLocation(program, 0, output);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("Shadow program link failed (" + vertex + "/" + fragment + "): "
                        + GL20.glGetProgramInfoLog(program, Math.max(1, GL20.glGetProgrami(program, GL20.GL_INFO_LOG_LENGTH))));
            ShadowGlDiagnostics.check("link shadow program " + vertex + "/" + fragment);
            complete = true;
            return new ShadowProgram(program);
        } finally {
            if (program != 0 && vs != 0) GL20.glDetachShader(program, vs);
            if (program != 0 && fs != 0) GL20.glDetachShader(program, fs);
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            if (!complete && program != 0) GL20.glDeleteProgram(program);
        }
    }

    private static int compile(int type, String name) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Cannot allocate shadow shader: " + name);
        boolean complete = false;
        try {
            var path = new ResourceLocation("neofontrender_ui_enhancements", "shaders/player_shadow/" + name);
            try (var resource = Minecraft.getMinecraft().getResourceManager().getResource(path);
                 var stream = resource.getInputStream()) {
                GL20.glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read player shadow shader " + path, failure);
            }
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("Player shadow shader compile failed (" + name + "): "
                        + GL20.glGetShaderInfoLog(shader, Math.max(1, GL20.glGetShaderi(shader, GL20.GL_INFO_LOG_LENGTH))));
            complete = true;
            return shader;
        } finally { if (!complete) GL20.glDeleteShader(shader); }
    }

    int uniform(String name) {
        return uniforms.computeIfAbsent(name, key -> {
            int location = GL20.glGetUniformLocation(handle, key);
            if (location < 0) throw new IllegalStateException("Missing required player shadow uniform: " + key);
            return location;
        });
    }
    void use() { GL20.glUseProgram(handle); }
    void integer(String name, int value) { GL20.glUniform1i(uniform(name), value); }
    void scalar(String name, float value) { GL20.glUniform1f(uniform(name), value); }
    void vector(String name, float x, float y, float z) { GL20.glUniform3f(uniform(name), x, y, z); }
    void matrix(String name, float[] values) {
        var buffer = BufferUtils.createFloatBuffer(values.length); buffer.put(values).flip();
        GL20.glUniformMatrix4(uniform(name), false, buffer);
    }
    @Override public void close() { GL20.glDeleteProgram(handle); }
}

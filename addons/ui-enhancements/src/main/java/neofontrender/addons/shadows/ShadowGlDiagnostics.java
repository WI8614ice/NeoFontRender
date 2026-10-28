package neofontrender.addons.shadows;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL11;

/** Checks the production operation boundary; never turns a rejected GL command into a valid frame. */
final class ShadowGlDiagnostics {
    private ShadowGlDiagnostics() {}

    static void check(String operation) {
        List<String> errors = new ArrayList<>();
        int error;
        while ((error = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            errors.add("0x" + Integer.toHexString(error));
            // A lost context may keep reporting CONTEXT_LOST. It cannot service further work.
            if (error == 0x0507) break;
        }
        if (!errors.isEmpty()) throw new IllegalStateException("Player shadow GL failure: " + operation + "; errors=" + errors);
    }
}

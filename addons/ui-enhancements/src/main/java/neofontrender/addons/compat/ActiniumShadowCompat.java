package neofontrender.addons.compat;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.Loader;

/** Vanilla targets register early; optional renderer classes resolve only during actual world rendering. */
public final class ActiniumShadowCompat {
    private ActiniumShadowCompat() {}

    public static boolean available() { return AvailabilityHolder.AVAILABLE; }

    public static boolean useModelRendererBatching() {
        return available() && Bindings.useModelRendererBatching();
    }

    public static boolean shaderPackOwnsShadows() {
        if (!available()) throw new IllegalStateException("Actinium shadow binding is unavailable");
        return Bindings.shaderPackOwnsShadows();
    }

    public static void pushAttributes(int mask) {
        if (!available()) throw new IllegalStateException("Actinium shadow binding is unavailable");
        Bindings.pushAttributes(mask);
    }

    public static void popAttributes() { Bindings.popAttributes(); }

    public static int matrixStackDepth() { return Bindings.matrixStackDepth(); }
    public static int matrixStackCapacity(int mode) { return Bindings.matrixStackCapacity(mode); }


    // First queried by rendering, after Forge has discovered the mod classpath. Never load optional types to detect them.
    private static final class AvailabilityHolder {
        private static final boolean AVAILABLE = Loader.isModLoaded("actinium")
                && present("com/dhj/actinium/render/terrain/ActiniumWorldRenderer.class")
                && present("dhj/embeddedt/embeddium/impl/render/chunk/ChunkRenderMatrices.class")
                && present("net/irisshaders/iris/api/v0/IrisApi.class");

        private static boolean present(String resource) {
            return Launch.classLoader != null && Launch.classLoader.getResource(resource) != null;
        }
    }

    private static final class Bindings {
        private static int matrixStackDepth() {
            return GLStateManager.getMatrixStackDepth(GLStateManager.getMatrixStack());
        }

        private static int matrixStackCapacity(int mode) {
            return switch (mode) {
                case 0x1700 -> GLStateManager.MAX_MODELVIEW_STACK_DEPTH;
                case 0x1701 -> GLStateManager.MAX_PROJECTION_STACK_DEPTH;
                case 0x1702 -> GLStateManager.MAX_TEXTURE_STACK_DEPTH;
                default -> throw new IllegalArgumentException("Unsupported player model matrix mode " + mode);
            };
        }

        private static void pushAttributes(int mask) {
            // This entry enforces the actual software stack capacity before mutating it.
            GLStateManager.glPushAttrib(mask);
        }

        private static void popAttributes() {
            GLStateManager.glPopAttrib();
        }

        private static boolean useModelRendererBatching() {
            return ActiniumRuntimeOptions.useModelRendererBatching();
        }

        private static boolean shaderPackOwnsShadows() {
            var iris = IrisApi.getInstance();
            return iris.isShaderPackInUse() || iris.isRenderingShadowPass();
        }
    }
}

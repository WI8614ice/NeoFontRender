package neofontrender.addons.shadows;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/** Full raw scope plus the Minecraft caches actually mutated by the living-model and armor/item pipeline. */
final class PlayerModelGlState implements AutoCloseable {
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
    private final boolean lighting = enabled(GL11.GL_LIGHTING), colorMaterial = enabled(GL11.GL_COLOR_MATERIAL);
    private final boolean depth = enabled(GL11.GL_DEPTH_TEST), alpha = enabled(GL11.GL_ALPHA_TEST);
    private final boolean blend = enabled(GL11.GL_BLEND), cull = enabled(GL11.GL_CULL_FACE), fog = enabled(GL11.GL_FOG);
    private final boolean rescale = enabled(GL12.GL_RESCALE_NORMAL), normalize = enabled(GL11.GL_NORMALIZE);
    private final boolean polygonOffset = enabled(GL11.GL_POLYGON_OFFSET_FILL);
    private final boolean[] lights = new boolean[8];
    private final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
    private final float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
    private final int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE), shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
    private final int colorFace = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_FACE), colorMode = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER);
    private final float offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
    private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
    private final FloatBuffer color = read(GL11.GL_CURRENT_COLOR, 4);
    private final boolean[] mask = colourMask();
    private final List<Texture> textures = new ArrayList<>();
    private final List<Matrix> matrices = new ArrayList<>();
    private final ShadowGlScope raw;
    private boolean closed;

    PlayerModelGlState() {
        for (int i = 0; i < lights.length; i++) lights[i] = enabled(GL11.GL_LIGHT0 + i);
        raw = ShadowGlScope.open();
        try {
            matrices.add(Matrix.push(GL11.GL_PROJECTION, GL11.GL_PROJECTION_MATRIX,
                    GL11.GL_PROJECTION_STACK_DEPTH, GL11.GL_MAX_PROJECTION_STACK_DEPTH));
            matrices.add(Matrix.push(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_MATRIX,
                    GL11.GL_MODELVIEW_STACK_DEPTH, GL11.GL_MAX_MODELVIEW_STACK_DEPTH));
            for (int unit : textureUnits(activeTexture)) {
                GlStateManager.setActiveTexture(unit);
                textures.add(new Texture(unit, enabled(GL11.GL_TEXTURE_2D), GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)));
                matrices.add(Matrix.push(GL11.GL_TEXTURE, GL11.GL_TEXTURE_MATRIX,
                        GL11.GL_TEXTURE_STACK_DEPTH, GL11.GL_MAX_TEXTURE_STACK_DEPTH));
            }
            GlStateManager.setActiveTexture(activeTexture);
            GlStateManager.matrixMode(matrixMode);
            ShadowGlDiagnostics.check("capture first-person player model state");
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void worldMatrices(TerrainSceneFrame.Projection projection) {
        GlStateManager.matrixMode(GL11.GL_PROJECTION); GL11.glLoadMatrix(buffer(projection.projectionMatrix()));
        GlStateManager.matrixMode(GL11.GL_MODELVIEW); GL11.glLoadMatrix(buffer(projection.modelViewMatrix()));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (Runnable action : new Runnable[] {this::restoreMatrices, raw::close, this::restoreCaches}) {
            try { action.run(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private void restoreMatrices() {
        Throwable failure = null;
        for (int i = matrices.size() - 1; i >= 0; i--) {
            try { matrices.get(i).restore(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        GlStateManager.setActiveTexture(activeTexture); GlStateManager.matrixMode(matrixMode);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private void restoreCaches() {
        if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
        for (int i = 0; i < lights.length; i++) {
            if (lights[i]) GlStateManager.enableLight(i); else GlStateManager.disableLight(i);
        }
        if (colorMaterial) GlStateManager.enableColorMaterial(); else GlStateManager.disableColorMaterial();
        GlStateManager.colorMaterial(colorFace, colorMode);
        if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
        GlStateManager.depthMask(depthWrite); GlStateManager.depthFunc(depthFunc);
        if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
        GlStateManager.alphaFunc(alphaFunc, alphaRef);
        if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
        GlStateManager.cullFace(cullFace == GL11.GL_FRONT ? GlStateManager.CullFace.FRONT
                : cullFace == GL11.GL_FRONT_AND_BACK ? GlStateManager.CullFace.FRONT_AND_BACK : GlStateManager.CullFace.BACK);
        if (fog) GlStateManager.enableFog(); else GlStateManager.disableFog();
        if (rescale) GlStateManager.enableRescaleNormal(); else GlStateManager.disableRescaleNormal();
        if (normalize) GlStateManager.enableNormalize(); else GlStateManager.disableNormalize();
        if (polygonOffset) GlStateManager.enablePolygonOffset(); else GlStateManager.disablePolygonOffset();
        GlStateManager.doPolygonOffset(offsetFactor, offsetUnits);
        GlStateManager.shadeModel(shade); GlStateManager.colorMask(mask[0], mask[1], mask[2], mask[3]);
        GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
        for (var texture : textures) {
            GlStateManager.setActiveTexture(texture.unit);
            if (texture.enabled) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            GlStateManager.bindTexture(texture.binding);
        }
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
        GlStateManager.setActiveTexture(activeTexture); GlStateManager.matrixMode(matrixMode);
        ShadowGlDiagnostics.check("restore first-person player model caches");
    }

    private record Texture(int unit, boolean enabled, int binding) {}
    private record Matrix(int mode, int textureUnit, int depthEnum, int depth, FloatBuffer value) {
        static Matrix push(int mode, int valueEnum, int depthEnum, int maximumEnum) {
            GlStateManager.matrixMode(mode);
            int depth = GL11.glGetInteger(depthEnum);
            if (depth >= GL11.glGetInteger(maximumEnum)) throw new IllegalStateException("No player model matrix stack slot mode=" + mode);
            var matrix = new Matrix(mode, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), depthEnum, depth, read(valueEnum, 16));
            GlStateManager.pushMatrix();
            try { ShadowGlDiagnostics.check("push player model matrix mode=" + mode); }
            catch (RuntimeException | Error failure) {
                try { matrix.restore(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return matrix;
        }
        void restore() {
            GlStateManager.setActiveTexture(textureUnit); GlStateManager.matrixMode(mode);
            int current = GL11.glGetInteger(depthEnum);
            if (current < depth) throw new IllegalStateException("Player renderer consumed caller matrix stack mode=" + mode);
            while (current-- > depth) GlStateManager.popMatrix();
            GL11.glLoadMatrix(value.duplicate());
        }
    }
    private static List<Integer> textureUnits(int active) {
        var units = new ArrayList<Integer>(List.of(GL13.GL_TEXTURE0, GL13.GL_TEXTURE1, GL13.GL_TEXTURE2));
        if (!units.contains(active)) units.add(active);
        return units;
    }
    private static boolean enabled(int capability) { return GL11.glIsEnabled(capability); }
    private static FloatBuffer read(int parameter, int size) {
        var data = BufferUtils.createFloatBuffer(size); GL11.glGetFloat(parameter, data); return data;
    }
    private static FloatBuffer buffer(float[] values) {
        var data = BufferUtils.createFloatBuffer(values.length); data.put(values).flip(); return data;
    }
    private static boolean[] colourMask() {
        var data = BufferUtils.createByteBuffer(4); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, data);
        return new boolean[] {data.get(0) != 0, data.get(1) != 0, data.get(2) != 0, data.get(3) != 0};
    }
}

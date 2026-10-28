package neofontrender.addons.chat;

import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Owns painter-order, straight-alpha composition for the base, hat and shadow quads. */
final class ChatAvatarGlState implements AutoCloseable {
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
    private final boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
    private final boolean fog = GL11.glIsEnabled(GL11.GL_FOG);
    private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final FloatBuffer color = readColor();
    private final boolean texture;
    private final int textureBinding;

    ChatAvatarGlState() {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.enableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        // Skin alpha and the chat fade multiply without an inherited alpha-test cutoff.
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
    }

    @Override
    public void close() {
        GlStateManager.tryBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
        GlStateManager.depthMask(depthWrite);
        if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
        if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
        if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
        if (fog) GlStateManager.enableFog(); else GlStateManager.disableFog();
        GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(textureBinding);
        if (texture) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(activeTexture);
    }

    private static FloatBuffer readColor() {
        FloatBuffer value = BufferUtils.createFloatBuffer(4);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, value);
        return value;
    }
}

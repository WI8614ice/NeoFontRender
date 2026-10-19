package neofontrender.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import neofontrender.addons.camera.CameraRuntime;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Local-player proximity alpha is confined to one world model render, including failures. */
@Mixin(RenderPlayer.class)
public abstract class MixinRenderPlayerCameraTransparency {
    /** Keep the player model in the world for player-anchored custom third-person rigs. */
    @Redirect(method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/client/entity/AbstractClientPlayer;isUser()Z"), require = 1)
    private boolean nfrUi$renderLocalPlayerForCameraMode(AbstractClientPlayer player) {
        return player.isUser() && !CameraRuntime.shouldRenderDetachedLocalPlayer()
                && !CameraRuntime.isPlayerAnchoredCameraActive();
    }

    @WrapMethod(method = {
            "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
            "func_76986_a(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V"
    }, remap = false, require = 1, expect = 1, allow = 1)
    private void nfrUi$renderPlayerAlpha(AbstractClientPlayer player, double x, double y,
                                        double z, float yaw, float partialTicks,
                                        Operation<Void> original) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (player == minecraft.player && CameraRuntime.isPlayerWorldRender(partialTicks)
                && CameraRuntime.shoulderSkipPlayerRendering()) return;
        float alpha = player == minecraft.player ? CameraRuntime.playerProximityAlpha(partialTicks) : 1.0F;
        if (alpha == 0.0F) return; // No invisible depth-only model at the fully transparent endpoint.
        boolean outerActive = CameraRuntime.isShoulderTransparencyRenderActive();
        if (alpha == 1.0F && !outerActive) {
            original.call(player, x, y, z, yaw, partialTicks);
            return;
        }
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        FloatBuffer color = BufferUtils.createFloatBuffer(4);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        float previousAlpha = CameraRuntime.exchangePlayerTransparencyRenderAlpha(1.0F);
        try {
            if (alpha < 1.0F) {
                GlStateManager.enableBlend();
                GlStateManager.enableAlpha();
                GlStateManager.depthMask(true);
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO);
                GlStateManager.alphaFunc(GL11.GL_GREATER, 1.0F / 255.0F);
                GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
                CameraRuntime.exchangePlayerTransparencyRenderAlpha(alpha);
            } else {
                // A remote player nested within the local draw must not inherit its opacity.
                GlStateManager.color(color.get(0), color.get(1), color.get(2), 1.0F);
            }
            original.call(player, x, y, z, yaw, partialTicks);
        } finally {
            CameraRuntime.exchangePlayerTransparencyRenderAlpha(1.0F);
            try {
                GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
                GlStateManager.alphaFunc(alphaFunc, alphaRef);
                GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                GlStateManager.depthMask(depthMask);
                if (blendEnabled) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
                if (alphaEnabled) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            } finally {
                CameraRuntime.exchangePlayerTransparencyRenderAlpha(previousAlpha);
            }
        }
    }
}

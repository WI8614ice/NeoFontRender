package neofontrender.addons.shadows;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderPlayer;
import neofontrender.addons.api.flight.PlayerShadowMode;
import neofontrender.addons.compat.YsmRenderPurpose;
import neofontrender.addons.shadows.access.PlayerRendererShadowState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Captures a missing local full body once into an owned scratch target, never into scene colour/depth. */
final class PlayerShadowProxy implements AutoCloseable {
    private int framebuffer, colour, depth;
    private boolean drawing, closed;

    static PlayerShadowProxy create() {
        var target = new PlayerShadowProxy();
        try {
            try (var state = ShadowGlScope.open()) {
                state.conventionalRaster();
                target.framebuffer = GL30.glGenFramebuffers(); target.colour = GL11.glGenTextures();
                target.depth = GL30.glGenRenderbuffers();
                if (target.framebuffer == 0 || target.colour == 0 || target.depth == 0)
                    throw new IllegalStateException("Cannot allocate local body shadow capture target");
                GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.colour);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, target.depth);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, 1, 1);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target.colour, 0);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, target.depth);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL11.GL_NONE);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Local body shadow capture target incomplete");
                ShadowGlDiagnostics.check("allocate local body shadow capture target");
            }
            return target;
        } catch (RuntimeException | Error failure) {
            try { target.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void captureMissing(AbstractClientPlayer player) {
        var frame = TerrainSceneFrame.current();
        if (closed || frame == null || frame.availability() != TerrainSceneFrame.Availability.READY
                || frame.shadowMode() != PlayerShadowMode.REAL_PLAYER_PROJECTION)
            throw new IllegalStateException("Local body proxy needs the current real-shadow frame");
        if (player.isSpectator() || player.isInvisible() || PlayerShadowCapture.hasCurrentCaster(player.getUniqueID())) return;
        if (drawing) throw new IllegalStateException("Recursive local body shadow proxy");
        var manager = Minecraft.getMinecraft().getRenderManager();
        Render<AbstractClientPlayer> renderer = manager.getEntityRenderObject(player);
        if (!(renderer instanceof RenderPlayer) || !(renderer instanceof PlayerRendererShadowState access)) {
            PlayerShadowCapture.unsupportedRenderer(player); return;
        }
        boolean outlines = access.nfrUi$shadowRenderOutlines();
        var oldView = manager.renderViewEntity;
        drawing = true;
        try (var state = new PlayerModelGlState()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, 1, 1);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.colorMask(true, true, true, true); GlStateManager.depthMask(true);
            GL11.glClearColor(0, 0, 0, 0); GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL20.glUseProgram(0);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableDepth(); GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableTexture2D(); GlStateManager.enableAlpha(); GlStateManager.disableFog();
            GlStateManager.color(1, 1, 1, 1);
            RenderHelper.enableStandardItemLighting();
            int brightness = player.isBurning() ? 15728880 : player.getBrightnessForRender();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightness & 65535, brightness >>> 16);
            var origin = frame.projection(); state.worldMatrices(origin);
            float t = frame.partialTicks();
            double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * t - origin.originX;
            double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * t - origin.originY;
            double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * t - origin.originZ;
            float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * t;
            manager.renderViewEntity = player; renderer.setRenderOutlines(false);
            YsmRenderPurpose.withWorldRender(() -> renderer.doRender(player, x, y, z, yaw, t));
            ShadowGlDiagnostics.check("capture missing first-person player body frame=" + frame.id());
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Local body shadow render failed", failure); }
        finally {
            try { renderer.setRenderOutlines(outlines); }
            finally { manager.renderViewEntity = oldView; drawing = false; }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (colour != 0) GL11.glDeleteTextures(colour);
        if (depth != 0) GL30.glDeleteRenderbuffers(depth);
    }
}

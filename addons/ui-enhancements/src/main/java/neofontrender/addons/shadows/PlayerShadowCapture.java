package neofontrender.addons.shadows;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.PlayerShadowMode;
import neofontrender.addons.compat.YsmRenderPurpose;
import neofontrender.addons.flight.PlayerBodyRenderScope;
import neofontrender.addons.shadows.access.ModelBoxShadowGeometry;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Collects geometry during the real body render, after animation/root sampling and before later models mutate it. */
public final class PlayerShadowCapture {
    public enum Availability { READY, NO_GEOMETRY, UNSUPPORTED_MODEL_GEOMETRY, NATIVE_MODEL_UNAVAILABLE,
        UNSUPPORTED_PLAYER_RENDERER, MODEL_RENDER_FAILED }
    public record Caster(UUID playerId, FlightBodyPose pose, Availability availability,
                         List<ShadowCasterMesh.Part> parts) {
        public Caster { parts = List.copyOf(parts); }
    }
    public record Subject(UUID playerId, FlightBodyPose pose) {}

    private static final Deque<Capture> STACK = new ArrayDeque<>();
    private static final Map<UUID, Caster> CASTERS = new LinkedHashMap<>();
    private static final Map<UUID, Subject> SUBJECTS = new LinkedHashMap<>();
    private static TerrainSceneFrame.WorldScope frame;

    private PlayerShadowCapture() {}

    public static void withPlayer(AbstractClientPlayer player, PlayerBodyRenderScope.RenderCall render) throws Throwable {
        var current = TerrainSceneFrame.current();
        if (current == null || current.availability() != TerrainSceneFrame.Availability.READY
                || YsmRenderPurpose.currentPurpose() != YsmRenderPurpose.Purpose.WORLD
                || player.isSpectator() || player.isInvisible()) {
            // A nested preview must fence the enclosing world's collector too.
            withFence(render);
            return;
        }
        var body = PlayerBodyRenderScope.current(player);
        FlightBodyPose pose = body == null ? FlightApi.queryBodyPose(player, current.partialTicks()) : body.pose();
        if (current.shadowMode() == PlayerShadowMode.REAL_PLAYER_PROJECTION)
            withCapture(current, player, player.getUniqueID(), pose, render);
        else withFence(render);
        rememberSubject(current, player, pose);
    }

    private static void adopt(TerrainSceneFrame.WorldScope current) {
        if (frame != current) { CASTERS.clear(); SUBJECTS.clear(); frame = current; }
    }

    private static void rememberSubject(TerrainSceneFrame.WorldScope current, AbstractClientPlayer player, FlightBodyPose pose) {
        adopt(current);
        SUBJECTS.putIfAbsent(player.getUniqueID(), new Subject(player.getUniqueID(), pose));
    }

    /** A first-person body may not have been drawn; sample only missing physical pose, never invent a model mesh. */
    public static void includeUnrenderedSubject(AbstractClientPlayer player) {
        var current = TerrainSceneFrame.current();
        if (current == null || current.availability() != TerrainSceneFrame.Availability.READY
                || player.isSpectator() || player.isInvisible()) return;
        if (frame == current && SUBJECTS.containsKey(player.getUniqueID())) return;
        rememberSubject(current, player, FlightApi.queryBodyPose(player, current.partialTicks()));
    }

    public static List<Subject> currentSubjects() {
        return frame != null && frame == TerrainSceneFrame.current() ? List.copyOf(SUBJECTS.values()) : List.of();
    }

    /** Frame/identity lease is independent of Minecraft model mutation and GL geometry sampling. */
    static void withCapture(TerrainSceneFrame.WorldScope current, Object player, UUID id,
                            FlightBodyPose pose, PlayerBodyRenderScope.RenderCall render) throws Throwable {
        if (current == null || TerrainSceneFrame.current() != current
                || current.availability() != TerrainSceneFrame.Availability.READY)
            throw new IllegalStateException("Player capture requires the current ready terrain frame");
        adopt(current);
        if (CASTERS.containsKey(id)) { withFence(render); return; }
        Capture capture = new Capture(player, pose);
        STACK.push(capture);
        try {
            render.run();
            CASTERS.put(id, new Caster(id, capture.pose,
                    capture.unavailable != null ? capture.unavailable
                            : capture.parts.isEmpty() ? Availability.NO_GEOMETRY : Availability.READY,
                    capture.unavailable == null ? capture.parts : List.of()));
        } finally {
            if (STACK.pop() != capture) throw new IllegalStateException("Player shadow capture closed out of order");
        }
    }

    private static void withFence(PlayerBodyRenderScope.RenderCall render) throws Throwable {
        Capture fence = new Capture(null, null);
        STACK.push(fence);
        try { render.run(); }
        finally {
            if (STACK.pop() != fence) throw new IllegalStateException("Player shadow fence closed out of order");
        }
    }

    /** Native YSM draw has no verified frozen-geometry contract; never substitute vanilla body parts. */
    public static void nativeModelUnavailable(Object player) {
        var current = TerrainSceneFrame.current();
        if (!(player instanceof AbstractClientPlayer rendered) || current == null
                || current.availability() != TerrainSceneFrame.Availability.READY
                || rendered.isSpectator() || rendered.isInvisible()
                || YsmRenderPurpose.currentPurpose() != YsmRenderPurpose.Purpose.WORLD) return;
        var body = PlayerBodyRenderScope.current(rendered);
        FlightBodyPose pose = body == null ? FlightApi.queryBodyPose(rendered, current.partialTicks()) : body.pose();
        rememberSubject(current, rendered, pose);
        if (current.shadowMode() != PlayerShadowMode.REAL_PLAYER_PROJECTION) return;
        var capture = STACK.peek();
        if (capture != null && capture.player == player) {
            capture.unavailable = Availability.NATIVE_MODEL_UNAVAILABLE;
        } else {
            CASTERS.put(rendered.getUniqueID(), new Caster(rendered.getUniqueID(), pose,
                    Availability.NATIVE_MODEL_UNAVAILABLE, List.of()));
        }
    }

    public static List<Caster> currentCasters() {
        return frame != null && frame == TerrainSceneFrame.current() ? List.copyOf(CASTERS.values()) : List.of();
    }

    public static boolean hasCurrentCaster(UUID player) {
        return frame != null && frame == TerrainSceneFrame.current() && CASTERS.containsKey(player);
    }

    public static void unsupportedRenderer(AbstractClientPlayer player) {
        var current = TerrainSceneFrame.current();
        if (current == null || current.availability() != TerrainSceneFrame.Availability.READY)
            throw new IllegalStateException("Unavailable renderer needs a current frame");
        adopt(current);
        CASTERS.putIfAbsent(player.getUniqueID(), new Caster(player.getUniqueID(), null,
                Availability.UNSUPPORTED_PLAYER_RENDERER, List.of()));
    }

    /** RenderLivingBase already logs the original failure; do not publish the triangles drawn before its catch. */
    public static void modelFailed() {
        if (frame == null || frame != TerrainSceneFrame.current()
                || frame.availability() != TerrainSceneFrame.Availability.READY) return;
        var capture = STACK.peek();
        if (capture != null && capture.player != null) capture.unavailable = Availability.MODEL_RENDER_FAILED;
    }

    public static void endWorld(TerrainSceneFrame.WorldScope closedFrame) {
        if (frame == closedFrame) {
            if (!STACK.isEmpty()) throw new IllegalStateException("Player capture outlived world frame");
            frame = null;
            CASTERS.clear();
            SUBJECTS.clear();
        }
    }

    /** Called before either vanilla or Actinium's batching path consumes this exact part state. */
    public static void modelPart(ModelRenderer model, float geometryScale, float transformScale,
                                 boolean rotationOnly) {
        if (frame == null || TerrainSceneFrame.current() != frame
                || frame.availability() != TerrainSceneFrame.Availability.READY) return;
        Capture capture = STACK.peek();
        if (capture == null || capture.player == null || capture.unavailable != null || model.isHidden || !model.showModel)
            return;
        var body = PlayerBodyRenderScope.current();
        if (body != null && body.entity() != capture.player) return;
        if (YsmRenderPurpose.currentPurpose() != YsmRenderPurpose.Purpose.WORLD) return;
        if (GL11.glGetInteger(GL11.GL_DEPTH_FUNC) == GL11.GL_EQUAL) return; // glint reuses existing surface depth
        if (model.getClass() != ModelRenderer.class || GL11.glGetInteger(GL11.GL_MATRIX_MODE) != GL11.GL_MODELVIEW) {
            capture.unavailable = Availability.UNSUPPORTED_MODEL_GEOMETRY;
            return;
        }
        List<TexturedQuad[]> boxes = new ArrayList<>();
        for (ModelBox box : model.cubeList) {
            if (box.getClass() != ModelBox.class) {
                capture.unavailable = Availability.UNSUPPORTED_MODEL_GEOMETRY;
                return;
            }
            boxes.add(((ModelBoxShadowGeometry) box).nfrUi$shadowQuads());
        }
        if (boxes.isEmpty()) return;
        float[] modelView;
        GlStateManager.pushMatrix();
        try {
            if (!rotationOnly) GlStateManager.translate(model.offsetX, model.offsetY, model.offsetZ);
            GlStateManager.translate(model.rotationPointX * transformScale, model.rotationPointY * transformScale,
                    model.rotationPointZ * transformScale);
            if (rotationOnly) {
                rotate(model.rotateAngleY, 0, 1, 0); rotate(model.rotateAngleX, 1, 0, 0); rotate(model.rotateAngleZ, 0, 0, 1);
            } else {
                rotate(model.rotateAngleZ, 0, 0, 1); rotate(model.rotateAngleY, 0, 1, 0); rotate(model.rotateAngleX, 1, 0, 0);
            }
            modelView = matrix(GL11.GL_MODELVIEW_MATRIX);
        } finally { GlStateManager.popMatrix(); }
        Matrix4f world = new Matrix4f().set(frame.projection().inverseModelView()).mul(new Matrix4f().set(modelView));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        try {
            boolean textured = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            var material = new ShadowCasterMesh.Material(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), textured,
                    alpha || blend, alpha ? GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) : GL11.GL_GREATER,
                    alpha ? GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) : 0);
            var part = ShadowCasterMesh.freeze(boxes, geometryScale, world.get(new float[16]),
                    matrix(GL11.GL_TEXTURE_MATRIX), material);
            if (part != null) capture.parts.add(part);
        } finally { GlStateManager.setActiveTexture(active); }
    }

    private static void rotate(float radians, float x, float y, float z) {
        if (radians != 0) GlStateManager.rotate(radians * (180.0F / (float) Math.PI), x, y, z);
    }
    private static float[] matrix(int matrix) {
        FloatBuffer values = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(matrix, values);
        float[] result = new float[16];
        values.get(result);
        return result;
    }
    private static final class Capture {
        private final Object player;
        private final FlightBodyPose pose;
        private final List<ShadowCasterMesh.Part> parts = new ArrayList<>();
        private Availability unavailable;
        private Capture(Object player, FlightBodyPose pose) { this.player = player; this.pose = pose; }
    }
}

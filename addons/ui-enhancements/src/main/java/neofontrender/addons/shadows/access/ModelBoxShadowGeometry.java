package neofontrender.addons.shadows.access;

import net.minecraft.client.model.TexturedQuad;

/** Immediate read of the actual inflated/mirrored vanilla model geometry; callers must freeze it. */
public interface ModelBoxShadowGeometry {
    TexturedQuad[] nfrUi$shadowQuads();
}

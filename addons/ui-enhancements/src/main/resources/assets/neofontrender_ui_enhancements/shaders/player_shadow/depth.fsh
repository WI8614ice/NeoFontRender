#version 150
uniform sampler2D materialTexture;
uniform int textured;
uniform int alphaFunction;
uniform float alphaReference;
uniform int ignoreSourceBlock;
uniform vec3 sourceBlockMin;
in vec3 skinCoordinate;
in vec3 worldPoint;
in float emission;
out float sourceEmission;
bool alphaPass(float a) {
    if (alphaFunction == 512) return false;
    if (alphaFunction == 513) return a < alphaReference;
    if (alphaFunction == 514) return a == alphaReference;
    if (alphaFunction == 515) return a <= alphaReference;
    if (alphaFunction == 516) return a > alphaReference;
    if (alphaFunction == 517) return a != alphaReference;
    if (alphaFunction == 518) return a >= alphaReference;
    return true;
}
void main() {
    // A light-emitting block's own surface does not seal a point source placed at its center.
    // Other emissive blocks still participate in occlusion.
    if (ignoreSourceBlock != 0 && all(greaterThanEqual(worldPoint, sourceBlockMin - vec3(0.00001)))
            && all(lessThanEqual(worldPoint, sourceBlockMin + vec3(1.00001)))) discard;
    if (textured != 0) {
        if (skinCoordinate.z == 0.0) discard;
        if (!alphaPass(texture(materialTexture, skinCoordinate.xy / skinCoordinate.z).a)) discard;
    } else if (!alphaPass(1.0)) discard;
    sourceEmission = emission;
}

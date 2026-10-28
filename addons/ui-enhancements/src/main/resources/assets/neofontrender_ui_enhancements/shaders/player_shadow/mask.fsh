#version 150
uniform sampler2D sceneDepth;
uniform sampler2D foregroundDepth;
uniform sampler2D worldDepth;
uniform sampler2D playerDepth;
uniform sampler2D sourceEmission;
uniform sampler2D receiverDepth;
uniform sampler2D receiverEmission;
uniform float receiverClearDepth;
uniform mat4 inverseProjectionView;
uniform mat4 lightProjectionView;
uniform mat4 inverseLightProjectionView;
uniform vec3 windowMapping;
uniform int zeroToOne;
uniform int upperLeft;
uniform vec3 screenSize;
uniform vec3 cameraPosition;
uniform vec3 lightVector;
uniform int directional;
uniform vec3 receiverCenter;
uniform float receiverRadius;
uniform float strength;
uniform float mapResolution;
uniform int axialDisc;
uniform vec3 discAnchor;
uniform vec3 discAxis;
uniform float discRadius;
uniform float discMaxDrop;
out float shadowMask;
vec3 lightPoint(vec2 uv, float depth) {
    vec4 point = inverseLightProjectionView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}
void main() {
    vec2 uv = gl_FragCoord.xy / screenSize.xy;
    float depth = texture(sceneDepth, uv).r;
    float normalizedDepth = (depth - windowMapping.x) / (windowMapping.y - windowMapping.x);
    vec4 point = inverseProjectionView * vec4(uv.x * 2.0 - 1.0,
        upperLeft != 0 ? 1.0 - uv.y * 2.0 : uv.y * 2.0 - 1.0,
        zeroToOne != 0 ? normalizedDepth : normalizedDepth * 2.0 - 1.0, 1.0);
    // Derivatives precede non-uniform discard, so neighbouring fragment lanes remain defined.
    vec3 receiver = point.xyz / (abs(point.w) > 1e-20 ? point.w : 1.0);
    vec3 crossNormal = cross(dFdx(receiver), dFdy(receiver));
    if (depth == windowMapping.z || texture(foregroundDepth, uv).r != depth || abs(point.w) <= 1e-20) discard;
    if (distance(receiver, receiverCenter) > receiverRadius) discard;
    float materialDepth = texture(receiverDepth, uv).r;
    if (materialDepth == receiverClearDepth) discard;
    vec4 materialPoint = inverseProjectionView * vec4(uv.x * 2.0 - 1.0,
        upperLeft != 0 ? 1.0 - uv.y * 2.0 : uv.y * 2.0 - 1.0,
        zeroToOne != 0 ? materialDepth : materialDepth * 2.0 - 1.0, 1.0);
    if (abs(materialPoint.w) <= 1e-20 || distance(materialPoint.xyz / materialPoint.w, receiver) > 0.003) discard;
    // Same camera pixel proves receiver material. Dilate one pixel conservatively at raster boundaries;
    // light-map PCF neighbours never establish that an emissive receiver is non-emissive.
    ivec2 materialPixel = ivec2(gl_FragCoord.xy);
    ivec2 materialSize = textureSize(receiverEmission, 0);
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx)
        if (texelFetch(receiverEmission, clamp(materialPixel + ivec2(dx, dy), ivec2(0), materialSize - ivec2(1)), 0).r > 0.0) discard;
    if (dot(crossNormal, crossNormal) < 1e-16) discard;
    vec3 normal = normalize(crossNormal);
    if (dot(normal, cameraPosition - receiver) < 0.0) normal = -normal;
    vec3 toLight = directional != 0 ? lightVector : normalize(lightVector - receiver);
    float facing = dot(normal, toLight);
    if (facing <= 0.0) discard; // direct-light occlusion must not re-darken an already unlit face
    float discOpacity = 1.0;
    if (axialDisc != 0) {
        vec3 offset = receiver - discAnchor;
        float drop = -dot(offset, discAxis);
        float radial = length(offset + discAxis * drop) / discRadius;
        if (drop < -0.003 || drop > discMaxDrop || radial >= 1.0) discard;
        discOpacity = (1.0 - smoothstep(0.3, 1.0, radial)) * (1.0 - max(0.0, drop) / discMaxDrop);
    }
    vec4 lightClip = lightProjectionView * vec4(receiver, 1.0);
    if (lightClip.w <= 0.0) discard;
    vec3 lightCoordinate = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    if (any(lessThan(lightCoordinate, vec3(0.0))) || any(greaterThan(lightCoordinate, vec3(1.0)))) discard;
    vec2 baseTexel = floor(lightCoordinate.xy * mapResolution - vec2(0.5));
    float shadow = 0.0;
    for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
        vec2 tap = (baseTexel + vec2(x, y) + vec2(0.5)) / mapResolution;
        if (any(lessThan(tap, vec2(0.0))) || any(greaterThan(tap, vec2(1.0)))) continue;
        float wd = texture(worldDepth, tap).r;
        float pd = texture(playerDepth, tap).r;
        if (wd >= 1.0 || (axialDisc == 0 && pd >= 1.0) || texture(sourceEmission, tap).r > 0.0) continue;
        vec3 nearPoint = lightPoint(tap, 0.0);
        vec3 ray = lightPoint(tap, 1.0) - nearPoint;
        float denominator = dot(normal, ray);
        if (abs(denominator) < 1e-7) continue;
        float t = dot(normal, receiver - nearPoint) / denominator;
        if (t < 0.0 || t > 1.0) continue;
        vec3 planeReceiver = nearPoint + ray * t;
        // Compare the real first world hit at this texel against the local visible receiver plane.
        // Scene depth alone cannot supply this light-path proof.
        vec3 worldHit = lightPoint(tap, wd);
        if (distance(worldHit, planeReceiver) > 0.003) continue;
        if (axialDisc != 0) shadow += 0.25;
        else {
            vec3 playerHit = lightPoint(tap, pd);
            if (dot(planeReceiver - playerHit, normalize(ray)) > 0.003) shadow += 0.25;
        }
    }
    shadowMask = shadow * min(0.35, 0.35 * strength) * facing * discOpacity;
}

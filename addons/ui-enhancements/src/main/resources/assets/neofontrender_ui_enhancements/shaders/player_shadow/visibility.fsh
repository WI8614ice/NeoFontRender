#version 150
uniform sampler2D worldDepth;
uniform sampler2D playerDepth;
uniform vec3 mapSize;
uniform mat4 inverseLightProjectionView;
out float visibilityResult;
vec3 worldPoint(vec2 uv, float depth) {
    vec4 p = inverseLightProjectionView * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}
void main() {
    vec2 uv = gl_FragCoord.xy / mapSize.xy;
    float player = texture(playerDepth, uv).r;
    float world = texture(worldDepth, uv).r;
    if (player >= 1.0) discard;
    if (world < 1.0) {
        vec3 ray = normalize(worldPoint(uv, 1.0) - worldPoint(uv, 0.0));
        if (dot(worldPoint(uv, world) - worldPoint(uv, player), ray) <= 0.003) discard;
    }
    visibilityResult = 1.0;
}

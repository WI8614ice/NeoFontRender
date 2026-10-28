#version 150
in vec3 position;
in vec3 auxiliary;
in float vertexAlpha;
uniform mat4 lightProjectionView;
uniform vec3 sectionOffset;
uniform int playerGeometry;
out vec3 skinCoordinate;
out vec3 worldPoint;
out float emission;
out float primaryAlpha;
void main() {
    worldPoint = position + sectionOffset;
    skinCoordinate = playerGeometry != 0 ? auxiliary : vec3(auxiliary.xy, 1.0);
    emission = playerGeometry != 0 ? 0.0 : auxiliary.z;
    primaryAlpha = playerGeometry != 0 ? clamp(vertexAlpha, 0.0, 1.0) : 1.0;
    gl_Position = lightProjectionView * vec4(worldPoint, 1.0);
}

#version 150
uniform sampler2D unionMask;
uniform vec3 screenSize;
uniform vec3 viewportOrigin;
out vec4 colour;
void main() {
    float mask = texture(unionMask, (gl_FragCoord.xy - viewportOrigin.xy) / screenSize.xy).r;
    colour = vec4(0.0, 0.0, 0.0, mask);
}

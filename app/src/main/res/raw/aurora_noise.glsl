#version 300 es
precision highp float;
uniform ivec4 uNoiseOrigins[7];
out vec4 fragColor;
float hash21(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

void main() {
 int row=int(gl_FragCoord.y);
 int kind=row<3?0:(row==3?1:(row<7?2:row-4));
 ivec4 origin=uNoiseOrigins[kind];
 vec2 cell=vec2(origin.xy)+vec2(int(gl_FragCoord.x),row-origin.z);
 fragColor=vec4(hash21(cell),hash21(cell+vec2(1.,0.)),
                hash21(cell+vec2(0.,1.)),hash21(cell+vec2(1.)));
}

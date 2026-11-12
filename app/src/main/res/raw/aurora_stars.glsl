#version 300 es
precision highp float;
uniform vec2 uResolution;
out vec4 fragColor;
float hash21(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise21(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),
               mix(hash21(i + vec2(0.0, 1.0)),
                   hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}

float starField(vec2 p, float scale, float seed) {
    vec2 grid = p * scale;
    vec2 cell = floor(grid);
    vec2 jitter = vec2(hash21(cell + seed), hash21(cell + seed + 12.7));
    float chosen = hash21(cell + 53.3 + seed);
    vec2 delta = fract(grid) - (0.18 + jitter * 0.64);
    float radius = mix(0.025, 0.070, pow(chosen, 11.0));
    float point = exp(-dot(delta, delta) / (radius * radius));
    float twinkle = 0.86 + 0.14 * sin(12.0 * (0.24 + chosen * 0.3) + seed + chosen * 91.0);
    return point * step(0.973, chosen) * mix(0.28, 0.92, jitter.x) * twinkle;
}

void main() {
 vec2 uv=gl_FragCoord.xy/uResolution;
 vec2 p=vec2((uv.x-.5)*uResolution.x/uResolution.y,uv.y-.5);
 float brightness=clamp((starField(p,98.,2.7)+starField(p,163.,18.9)*.42)*1.55,0.,1.);
 fragColor=vec4(.70,.79,.93,brightness);
}

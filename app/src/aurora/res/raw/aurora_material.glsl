#version 300 es
precision highp float;
uniform float uTime;
uniform vec4 uShapeA;
uniform vec4 uShapeB;
uniform float uGeometryExtent;
uniform float uGeometryWidth;
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

void main(){
 float t=uTime;
 float x=((gl_FragCoord.x-.5)/(uGeometryWidth-1.)*2.-1.)*uGeometryExtent;
 float largeFold=noise21(vec2(x*.80,t*.030));
 float front = 3.20 + (uShapeA.x + 0.16 * sin(t * 0.071)) * sin(x * uShapeA.y + uShapeA.z + 0.28 * sin(t * 0.047))
                           + 0.46 * sin(x * (uShapeA.y * 2.64) + uShapeA.w + 0.36 * sin(t * 0.063 + 1.2))
                           + 0.40 * (largeFold - 0.5);
 float frontSlope = (uShapeA.x + 0.16 * sin(t * 0.071)) * uShapeA.y * cos(x * uShapeA.y + uShapeA.z + 0.28 * sin(t * 0.047))
                         + 0.46 * (uShapeA.y * 2.64) * cos(x * (uShapeA.y * 2.64) + uShapeA.w + 0.36 * sin(t * 0.063 + 1.2));
 float frontNeighbour = 3.98 + (uShapeB.x + 0.13 * sin(t * 0.059 + 2.0)) * sin(x * uShapeB.y + uShapeB.z + 0.31 * sin(t * 0.041 + 1.4))
                             + 0.34 * sin(x * (uShapeB.y * 2.30) + uShapeB.w + 0.32 * sin(t * 0.057 + 2.3))
                             + 0.24 * (noise21(vec2(x * 0.67 + 5.4, t * 0.022 + 7.0)) - 0.5);
 float nearNeighbourSlope = (uShapeB.x + 0.13 * sin(t * 0.059 + 2.0)) * uShapeB.y * cos(x * uShapeB.y + uShapeB.z + 0.31 * sin(t * 0.041 + 1.4))
                                 + 0.34 * (uShapeB.y * 2.30) * cos(x * (uShapeB.y * 2.30) + uShapeB.w + 0.32 * sin(t * 0.057 + 2.3));
 fragColor=vec4(front,frontSlope,frontNeighbour,nearNeighbourSlope);
}

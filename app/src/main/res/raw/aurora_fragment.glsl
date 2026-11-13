#version 300 es
precision highp float;

uniform vec2 uResolution;
uniform float uTime;
uniform float uBass;
uniform float uMid;
uniform float uHigh;
uniform float uEnergy;
uniform float uBeat;
uniform float uIntensity;
uniform float uMotion;
uniform vec2 uDepthWind[96];
uniform vec4 uShapeA;
uniform vec4 uShapeB;
uniform vec2 uBandAge;
uniform vec2 uBandFade;
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
    float twinkle = 0.86 + 0.14 * sin(uTime * (0.24 + chosen * 0.3) + seed + chosen * 91.0);
    return point * step(0.973, chosen) * mix(0.28, 0.92, jitter.x) * twinkle;
}

// A straight bundle has a complete, independent life in material space:
// dark -> appear overhead -> reveal downwards -> full curtain -> fade -> dark.
// Return visibility, arrival at the lower edge, and a stable column seed.
vec3 columnLifecycle(float x, float t, float height, float seed, float bandOffset,
                     float period, float holdEnd, float fadeEnd) {
    float phaseSeed = noise21(vec2(x * 0.72 + seed * 7.1, seed * 3.7));
    float rateSeed = noise21(vec2(x * 0.51 + seed * 4.3, seed * 6.1));
    // Overlapping lifetimes allow both neighbouring pairs to coexist.
    // A small local phase spread keeps the downward arrivals irregular.
    float clock = t / period + 0.012 * sin(t * 0.055 + seed);
#ifdef FAR_LAYER
    float age = fract(clock + bandOffset + (phaseSeed - 0.5) * 0.085);
#else
    int band = bandOffset < 0.5 ? 0 : 1;
    // Birth is scheduled on the beat; local delays never reveal a column early.
    float age = clamp(uBandAge[band] - phaseSeed * 0.025, 0.0, 0.9999);
#endif
#ifdef FAR_LAYER
    float birth = smoothstep(0.0, 0.025, age);
    float downward = smoothstep(0.02, mix(0.12, 0.16, rateSeed), age);
#else
    float birth = smoothstep(0.0, 0.0125, age);
    float downward = smoothstep(0.01, mix(0.06, 0.08, rateSeed), age);
#endif
    float revealHeight = mix(1.50 + phaseSeed * 0.90, -0.48, downward);
    float reveal = smoothstep(revealHeight - 0.16, revealHeight + 0.16, height);
    float fade = 1.0 - smoothstep(holdEnd, fadeEnd, age);
#ifndef FAR_LAYER
    fade *= uBandFade[band];
#endif
    float arrival = 1.0 - smoothstep(-0.25, 0.10, revealHeight);
    return vec3(birth * reveal * fade, arrival, rateSeed);
}

// A view into two pairs of translucent, corrugated vertical sheets. Sampling
// in world depth, rather than drawing bars in screen coordinates, produces
// folded lower arcs, changing overlap, perspective and convergence overhead.
vec3 auroraVolume(vec2 screen, float aspect, float t) {
    float viewY = screen.y * 1.05;
    // Camera pitched 40 degrees upwards: the direction of the magnetic rays
    // converges above the image, instead of remaining parallel on screen.
    float viewZ = 0.766044 - 0.642788 * viewY;
    vec2 slope = vec2(screen.x * aspect * 1.10,
                     0.642788 + 0.766044 * viewY) / viewZ;
    vec3 sum = vec3(0.0);
    float response = clamp(uMotion, 0.0, 1.5);
    float bass = clamp(uBass * response, 0.0, 1.5);
    float mid = clamp(uMid * response, 0.0, 1.5);
    float high = clamp(uHigh * response, 0.0, 1.5);
    float energy = clamp(uEnergy * response, 0.0, 1.5);
    float beat = clamp(uBeat * response, 0.0, 1.5);

    // Bounded, reversing sway instead of endless lateral translation.
    // Fold phases and amplitudes breathe independently like a floating ribbon.
    // Geometry remains independent of height, keeping magnetic rays straight.
    float nearDrift = 0.34 * sin(t * 0.045) + 0.14 * (sin(t * 0.083 + 0.8) - sin(0.8));
    float farDrift = t * 0.032
                   + 0.18 * (sin(t * 0.031 + 1.30) - sin(1.30))
                   + 0.028 * (sin(t * 0.13 + 0.8) - sin(0.8));

    // Keep the approved soft volume integral, with 96 normalized samples.
    // A footprint-aware Gaussian avoids visible fixed-depth slice replicas.
    const float depthStep = 7.8 / 95.0;
    for (int i = 0; i < 96; ++i) {
        float z = 1.0 + float(i) * depthStep;
        vec3 p = vec3(slope * z, z);
        // All four lifecycle reveal masks are exactly zero below height -0.64.
        // Skip before evaluating geometry, noise, trigonometry or exponentials.
        if (p.y <= 2.0) continue;
        // Freeze lateral wind at the luminous base: XZ folds still move,
        // but individual rays stay straight in world space as they rise.
        // Shared by every pixel: computed once per depth sample on the CPU.
        float wind = uDepthWind[i].x;
        float dxDz = slope.x + uDepthWind[i].y;
        float x = p.x + wind - nearDrift;
        float rearX = p.x + wind - farDrift;

        // Broad folds and smaller eddies have incommensurate scales. The two
        // sheets share a field direction, while their waves evolve separately.
        float largeFold = noise21(vec2(x * 0.80, t * 0.030));
        float front = 3.20 + (uShapeA.x + 0.16 * sin(t * 0.071)) * sin(x * uShapeA.y + uShapeA.z + 0.28 * sin(t * 0.047))
                           + 0.46 * sin(x * (uShapeA.y * 2.64) + uShapeA.w + 0.36 * sin(t * 0.063 + 1.2))
                           + 0.40 * (largeFold - 0.5);
        float rear = 5.30 + 1.10 * sin(rearX * 0.58 + 1.30 + t * 0.004)
                          + 0.40 * sin(rearX * 2.30 - t * 0.012);
        float frontSlope = (uShapeA.x + 0.16 * sin(t * 0.071)) * uShapeA.y * cos(x * uShapeA.y + uShapeA.z + 0.28 * sin(t * 0.047))
                         + 0.46 * (uShapeA.y * 2.64) * cos(x * (uShapeA.y * 2.64) + uShapeA.w + 0.36 * sin(t * 0.063 + 1.2));
        float rearSlope = 1.10 * 0.58 * cos(rearX * 0.58 + 1.30 + t * 0.004)
                        + 0.40 * 2.30 * cos(rearX * 2.30 - t * 0.012);
        float frontFootprint = abs(1.0 - dxDz * frontSlope) * depthStep;
        float rearFootprint = abs(1.0 - dxDz * rearSlope) * depthStep;
        float frontWidth = sqrt(0.20 * 0.20 + frontFootprint * frontFootprint / 6.0);
        float rearWidth = sqrt(0.25 * 0.25 + rearFootprint * rearFootprint / 6.0);
        // Each original sheet gets a neighbouring, softly corrugated companion.
        // The separations vary gently, so nearby sheets can merge and part.
        float frontNeighbour = 3.98 + (uShapeB.x + 0.13 * sin(t * 0.059 + 2.0)) * sin(x * uShapeB.y + uShapeB.z + 0.31 * sin(t * 0.041 + 1.4))
                             + 0.34 * sin(x * (uShapeB.y * 2.30) + uShapeB.w + 0.32 * sin(t * 0.057 + 2.3))
                             + 0.24 * (noise21(vec2(x * 0.67 + 5.4, t * 0.022 + 7.0)) - 0.5);
        float rearNeighbour = 6.20 + 0.84 * sin(rearX * 0.73 + 0.96 - t * 0.004)
                            + 0.28 * sin(rearX * 1.87 + 2.10 + t * 0.016)
                            + 0.23 * (noise21(vec2(rearX * 0.91 + 9.2, t * 0.019 + 3.0)) - 0.5);
        float nearNeighbourSlope = (uShapeB.x + 0.13 * sin(t * 0.059 + 2.0)) * uShapeB.y * cos(x * uShapeB.y + uShapeB.z + 0.31 * sin(t * 0.041 + 1.4))
                                 + 0.34 * (uShapeB.y * 2.30) * cos(x * (uShapeB.y * 2.30) + uShapeB.w + 0.32 * sin(t * 0.057 + 2.3));
        float farNeighbourSlope = 0.84 * 0.73 * cos(rearX * 0.73 + 0.96 - t * 0.004)
                                + 0.28 * 1.87 * cos(rearX * 1.87 + 2.10 + t * 0.016);
        float nearNeighbourFootprint = abs(1.0 - dxDz * nearNeighbourSlope) * depthStep;
        float farNeighbourFootprint = abs(1.0 - dxDz * farNeighbourSlope) * depthStep;
        float nearNeighbourWidth = sqrt(0.18 * 0.18 + nearNeighbourFootprint * nearNeighbourFootprint / 6.0);
        float farNeighbourWidth = sqrt(0.22 * 0.22 + farNeighbourFootprint * farNeighbourFootprint / 6.0);
        float df = (z - front) / frontWidth;
        float dr = (z - rear) / rearWidth;
        float dfn = (z - frontNeighbour) / nearNeighbourWidth;
        float drn = (z - rearNeighbour) / farNeighbourWidth;
        // Even the widest halo is below exp(-12.8) beyond eight sigmas.
#ifdef FAR_LAYER
        if (min(abs(dr),abs(drn)) > 8.0) continue;
#else
        if (min(abs(df),abs(dfn)) > 8.0) continue;
#endif
#ifndef FAR_LAYER
        float frontDensity = exp(-df * df) * (0.20 / frontWidth) * 0.90;
#else
        const float frontDensity = 0.0;
#endif
#ifdef FAR_LAYER
        float rearDensity = exp(-dr * dr) * (0.25 / rearWidth) * 0.78 * 0.90;
#else
        const float rearDensity = 0.0;
#endif
#ifndef FAR_LAYER
        float frontNeighbourDensity = exp(-dfn * dfn) * (0.20 / nearNeighbourWidth) * 0.58;
#else
        const float frontNeighbourDensity = 0.0;
#endif
#ifdef FAR_LAYER
        float rearNeighbourDensity = exp(-drn * drn) * (0.25 / farNeighbourWidth) * 0.78 * 0.58;
#else
        const float rearNeighbourDensity = 0.0;
#endif
        vec3 nearCycle = columnLifecycle(x, t, p.y - 2.64, 1.3, 0.10, 56.0, 0.70, 0.92);
        vec3 farCycle = columnLifecycle(rearX, t, p.y - 2.64, 4.7, 0.02, 59.0, 0.76, 0.91);
        // Independent long lifetimes and starting phases allow varied overlaps.
        vec3 nearNeighbourCycle = columnLifecycle(x + 0.33, t, p.y - 2.64, 2.1, 0.60, 56.0, 0.64, 0.88);
        vec3 farNeighbourCycle = columnLifecycle(rearX - 0.27, t, p.y - 2.64, 5.6, 0.24, 67.0, 0.73, 0.93);
#ifndef FAR_LAYER
        float nearPresence = nearCycle.x;
#else
        const float nearPresence = 0.0;
#endif
#ifdef FAR_LAYER
        float farPresence = farCycle.x;
#else
        const float farPresence = 0.0;
#endif
#ifndef FAR_LAYER
        float nearNeighbourPresence = nearNeighbourCycle.x;
#else
        const float nearNeighbourPresence = 0.0;
#endif
#ifdef FAR_LAYER
        float farNeighbourPresence = farNeighbourCycle.x;
#else
        const float farNeighbourPresence = 0.0;
#endif
        // Exactly zero emission: avoid all remaining fibre and halo work.
        if (nearPresence + farPresence + nearNeighbourPresence + farNeighbourPresence == 0.0) continue;
        vec4 visible = vec4(frontDensity * nearPresence, rearDensity * farPresence,
                            frontNeighbourDensity * nearNeighbourPresence,
                            rearNeighbourDensity * farNeighbourPresence);
        float density = dot(visible, vec4(1.0));
        float baseArrival = dot(visible, vec4(nearCycle.y, farCycle.y,
                              nearNeighbourCycle.y, farNeighbourCycle.y)) / (density + 0.0001);
        float columnSeed = dot(visible, vec4(nearCycle.z, farCycle.z,
                             nearNeighbourCycle.z, farNeighbourCycle.z)) / (density + 0.0001);
        // Distinct material coordinates prevent a duplicated fibre pattern.
        float fullDensity = frontDensity + rearDensity + frontNeighbourDensity + rearNeighbourDensity;
        float fibreX = (x * frontDensity + rearX * rearDensity
                      + (x + 0.33) * frontNeighbourDensity
                      + (rearX - 0.27) * rearNeighbourDensity) / (fullDensity + 0.0001);

        float grain = noise21(vec2(fibreX * 16.0 - t * 0.180,
                                   z * 0.12 + 2.64 * 0.07 + t * 0.017));
        float base = 2.64 + (grain - 0.5) * 0.055;
        float height = p.y - base;
        float haloDensity = exp(-df * df * 0.20) * (0.20 / frontWidth) * nearPresence * 0.90
                          + exp(-dr * dr * 0.20) * (0.25 / rearWidth) * 0.65 * farPresence * 0.90
                          + exp(-dfn * dfn * 0.20) * (0.20 / nearNeighbourWidth) * nearNeighbourPresence * 0.58
                          + exp(-drn * drn * 0.20) * (0.25 / farNeighbourWidth) * 0.65 * farNeighbourPresence * 0.58;
        float halo = exp(-abs(height - 0.08) * 3.2) * haloDensity * 0.052;
        // Halo-only samples do not need fibre noise, packets or palette mixing.
        // The omitted body contribution is bounded and checked by pixel QA.
        if (density < 0.000001) {
            sum += vec3(0.11, 1.00, 0.51) * halo * 0.275 * (55.0 / 95.0);
            continue;
        }
        float silk = noise21(vec2(fibreX * 51.0 - t * 0.320,
                                  2.64 * 0.15 + t * 0.022));
        float rays = (0.15 + 0.85 * grain) * (0.50 + 0.50 * silk);
        float lengthField = noise21(vec2(fibreX * 2.80, z * 0.20 + t * 0.015));
        float rayLength = 0.90 + lengthField * 0.46;
        // A soft packet travels from high atmosphere down each straight ray:
        // increasing time in height + time*speed moves a packet toward -Y.
        // Per-bundle speed and phase prevent a shared horizontal scan line.
        float fallSpeed = 0.18 + columnSeed * 0.16;
        float fallPhase = columnSeed * 7.4 + grain * 0.20 + z * 0.20;
        float descending = noise21(vec2(fibreX * 1.85 + z * 0.27,
                                         height * 1.10 + t * fallSpeed + fallPhase));
        float packet = smoothstep(0.26, 0.78, descending);
        float flowLight = 0.72 + packet * 0.56;
        float veil = smoothstep(-0.19, 0.08, height)
                   * exp(-max(height - 0.08, 0.0) * (1.30 / rayLength));
        float rim = exp(-pow(height / 0.115, 2.0)) * 2.3;

        // Green lower folds, mint core, a blue transition and dilute violet
        // high above. Purple is emitted in the thin upper veil, not a tint
        // washed across the black sky.
        vec3 green = vec3(0.11, 1.00, 0.51);
        vec3 mint = vec3(0.36, 1.00, 0.72);
        vec3 cyan = vec3(0.10, 0.55, 0.72);
        vec3 violet = vec3(0.46, 0.16, 0.69);
        vec3 color = mix(green, cyan, smoothstep(0.20, 1.12, height) * 0.62);
        color = mix(color, violet, smoothstep(0.55, 1.85, height) * 0.80);
        vec3 rimColor = mix(green, mint, 0.22 + grain * 0.23);

        float musical = 0.83 + energy * 0.19 + bass * 0.15 + mid * 0.07;
        float localBeat = beat * smoothstep(0.54, 0.85, grain) * 0.26;
        float fineLight = 1.0 + high * silk * 0.18 + localBeat;
        float pinkPatch = smoothstep(0.22, 0.60, lengthField);
        float arrival = baseArrival * (0.45 + 0.55 * smoothstep(0.36, 0.77, descending));
        float pinkFringe = exp(-pow((height + 0.18) / 0.15, 2.0))
                         * pinkPatch * arrival * 1.65;
        vec3 pinkColor = vec3(0.95, 0.30, 0.65);
        float pinkTint = (1.0 - smoothstep(-0.20, -0.035, height))
                       * pinkPatch * arrival * 0.65;
        vec3 lowerRimColor = mix(rimColor, pinkColor, pinkTint);
        vec3 light = (color * veil * rays * 1.65 * fineLight
                   + lowerRimColor * rim * 0.59) * flowLight
                   + pinkColor * pinkFringe;

        // The wider density kernel integrates a faint optical halo around
        // the bright folds. It is geometrically aligned, never a flat panel.

        sum += (light * density * musical + green * halo) * 0.275 * (55.0 / 95.0);
    }
    return sum;
}

void main() {
    vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
    float t = uTime;

    vec3 color = vec3(0.0);
    vec3 aurora = auroraVolume(uv - 0.5, aspect, t);
    float rightShade = mix(1.0, 0.78, smoothstep(0.44, 0.99, uv.x));
    float lowerShade = mix(0.35, 1.0, smoothstep(0.02, 0.40, uv.y));
    color += aurora * rightShade * lowerShade * clamp(uIntensity, 0.0, 2.0);

    fragColor = vec4(1.0 - exp(-max(color,0.0) * 1.72), 1.0);
}

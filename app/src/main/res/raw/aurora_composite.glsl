#version 300 es
precision highp float;
uniform vec2 uResolution;
uniform sampler2D uNear;
uniform sampler2D uFar;
uniform float uFarBrightness;
uniform float uStarPulse;
uniform highp sampler2D uStars;
uniform bool uCacheShade;
uniform bool uCompactSky;
uniform vec3 uStarColour;
out vec4 fragColor;

uniform highp sampler2D uCached0;
uniform highp sampler2D uCached1;
uniform highp sampler2D uCached2;
uniform highp sampler2D uCached3;
uniform float uCachedBlend;
uniform bool uCachedOutputOnly;
uniform vec2 uCachedFractions;
uniform vec4 uCachedLife;
uniform float uCachedMusic;
uniform float uCachedAspect;
vec3 cachedBand(vec4 field,float age,float fade,float holdEnd,float fadeEnd,vec2 uv) {
    float threshold=field.a*.12;
    float birth=smoothstep(0.,.0125,age)*smoothstep(threshold-.0035,threshold+.0035,age);
    float death=1.-smoothstep(holdEnd+.0125,fadeEnd+.0125,age);
    float shade=mix(1.,.78,smoothstep(.44,.99,uv.x))*mix(.35,1.,smoothstep(.02,.40,uv.y));
    float exposure=max(0.,birth*death*fade*uCachedMusic*shade);
    return 1.-pow(max(1.-field.rgb,vec3(.00001)),vec3(exposure));
}
vec3 cachedNear(vec2 uv) {
    if(uCachedBlend<=0.)return texture(uNear,uv).rgb;
    vec3 live=vec3(0.);
    if(uCachedBlend<1.)live=texture(uNear,uv).rgb;
    vec2 sampleUV=vec2((uv.x-.5)*uCachedAspect/3.+.5,1.-uv.y);
    vec4 a=mix(texture(uCached0,sampleUV),texture(uCached1,sampleUV),uCachedFractions.x);
    vec4 b=mix(texture(uCached2,sampleUV),texture(uCached3,sampleUV),uCachedFractions.y);
    vec3 ca=cachedBand(a,uCachedLife.x,uCachedLife.z,.70,.92,uv);
    vec3 cb=cachedBand(b,uCachedLife.y,uCachedLife.w,.64,.88,uv);
    return mix(live,1.-(1.-ca)*(1.-cb),uCachedBlend);
}

float staticShade(vec2 uv) {
 float vignette=1.-.19*smoothstep(.20,.87,length(uv-.5));
 float horizontal=uv.x<.48?mix(0.,.04,clamp((uv.x-.28)/.20,0.,1.)):mix(.04,.24,(uv.x-.48)/.52);
 float sy=1.-uv.y;
 float vertical=sy<.38?mix(.02,.04,sy/.38):mix(.04,.50,(sy-.38)/.62);
 return vignette*(1.-horizontal)*(1.-vertical);
}

void main() {
 vec2 uv=gl_FragCoord.xy/uResolution;
 if(uCachedOutputOnly) {fragColor=vec4(cachedNear(uv),1.);return;}
 vec3 aurora=1.-(1.-cachedNear(uv))*(1.-texture(uFar,uv).rgb*uFarBrightness);
 // RG8: full-resolution star alpha plus affine low-frequency shade.
 if(uCompactSky) {
  vec2 sky=texelFetch(uStars,ivec2(gl_FragCoord.xy),0).rg;
  float alpha=clamp(sky.r*(.90+1.45*uStarPulse),0.,1.);
  vec3 color=mix(aurora,uStarColour,alpha);
  fragColor=vec4(color*(sky.g*.70+.30),1.);
  return;
 }
 vec4 stars=uCacheShade?texelFetch(uStars,ivec2(gl_FragCoord.xy),0):texture(uStars,uv);
 // Black base, cached far pair, animated near pair, then alpha-brightness stars.
 // Preserve positions and full-resolution cache; only star alpha responds to rhythm.
 float pulseAlpha=clamp(stars.a*(.90+1.45*uStarPulse),0.,1.);
 vec3 color=mix(aurora,uCacheShade?uStarColour:stars.rgb,pulseAlpha);
 if(uCacheShade) {
  float shade=(stars.r*256.+stars.g)/257.;
  if(gl_FragCoord.x<1. && gl_FragCoord.y<1.)shade=staticShade(uv);
  fragColor=vec4(color*shade,1.);
  return;
 }
 color*=1.-.19*smoothstep(.20,.87,length(uv-.5));
 float h=uv.x<.48?mix(0.,.04,clamp((uv.x-.28)/.20,0.,1.)):mix(.04,.24,(uv.x-.48)/.52);
 float sy=1.-uv.y;
 float v=sy<.38?mix(.02,.04,sy/.38):mix(.04,.50,(sy-.38)/.62);
 fragColor=vec4(color*(1.-h)*(1.-v),1.);
}

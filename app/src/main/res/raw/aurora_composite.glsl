#version 300 es
precision highp float;
uniform vec2 uResolution;
uniform sampler2D uNear;
uniform sampler2D uFar;
uniform float uFarBrightness;
uniform float uStarPulse;
uniform sampler2D uStars;
out vec4 fragColor;
void main() {
 vec2 uv=gl_FragCoord.xy/uResolution;
 vec3 aurora=1.-(1.-texture(uNear,uv).rgb)*(1.-texture(uFar,uv).rgb*uFarBrightness);
 vec4 stars=texture(uStars,uv);
 // Black base, cached far pair, animated near pair, then alpha-brightness stars.
 // Preserve positions and full-resolution cache; only star alpha responds to rhythm.
 float pulseAlpha=clamp(stars.a*(.65+1.45*uStarPulse),0.,1.);
 vec3 color=mix(aurora,stars.rgb,pulseAlpha);
 color*=1.-.19*smoothstep(.20,.87,length(uv-.5));
 float h=uv.x<.48?mix(0.,.04,clamp((uv.x-.28)/.20,0.,1.)):mix(.04,.24,(uv.x-.48)/.52);
 float sy=1.-uv.y;
 float v=sy<.38?mix(.02,.04,sy/.38):mix(.04,.50,(sy-.38)/.62);
 fragColor=vec4(color*(1.-h)*(1.-v),1.);
}

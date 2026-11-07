# Playing visual verification

The player keeps album-derived light surfaces and dark, readable text. Background
light accents use a separate extraction pass: sufficiently large, saturated cover
regions outrank broad muted colors. White borders, near-black noise, transparent
pixels and isolated colored pixels do not dilute that selection. Dark colorful
accents are lifted in brightness without adding white; a second hue is used only
when it actually exists in the cover. Monochrome covers remain neutral.

A cached white reading gradient protects the text column and header independently
of the vivid lights. PlayerPalette checks foreground contrast against its 70%
white floor as well as the original pastel surfaces. The light colors themselves
are not whitened into background-surface colors. Three cached
radial gradients drift independently between random positions, sizes and opacity
targets, taking 18–32 seconds per transition. A quintic interpolation eases every
boundary. The existing low-frequency PCM spectrum drives a slow brightness envelope;
there is no extra JNI sampling or live blur pass.

The background clock runs only while playback is active and the Activity lifecycle
is STARTED. Pause/background cancels it without resetting light positions. Resume
starts a fresh timestamp baseline so time away cannot fast-forward the effect.
A delayed foreground frame is bounded as well. Colors can still crossfade normally
when a new album cover arrives.

Frame updates only invalidate graphics-layer transform/alpha properties. Radial
shaders remain cached, no keyframes are allocated per frame, and ModulateAlpha
avoids intermediate offscreen buffers for the translucent single-rectangle layers.
The light layers are enlarged so all translated rectangle edges remain outside the
clipped viewport at every allowed scale and position. Record and progress rendering
remain independent.

After installing the CI APK, verify several minutes of playback at 4:3 and 16:9,
vivid accents on mostly neutral covers, readable text/header, light drift and gentle
musical breathing, pause/resume continuity, no jump after
returning from desktop, cover/theme changes, readable text, and smooth record
rotation. These rendering/performance checks require a device and are not implied
by the palette contrast unit tests.

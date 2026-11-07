# Playing visual verification

The player keeps album-derived light colors and dark, readable text. Three cached
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
light drift and gentle musical breathing, pause/resume continuity, no jump after
returning from desktop, cover/theme changes, readable text, and smooth record
rotation. These rendering/performance checks require a device and are not implied
by the palette contrast unit tests.

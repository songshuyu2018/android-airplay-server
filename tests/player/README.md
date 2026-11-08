# Playing visual verification

The player uses the original single, continuous background: a diagonal pastel
base and three broad, overlapping radial gradients at the original centers/radii.
There are no separate reading panels, horizontal/vertical white masks, moving
full-screen rectangles or transformed rectangular color layers. Each glow is
painted as a circle with zero alpha at its outer boundary, transformed within
one full-screen drawing surface. Overlap and gentle elliptical deformation make
an irregular wash without exposing any internal rectangular edge.

The separate vivid-cover extraction remains: sufficiently large saturated regions
outrank muted regions; white borders, transparent pixels and isolated colored noise
do not dilute them. The selected hues become soft light colors with a 64% white
mix. Every painted RGB channel is at least 163, so all gradient interpolation and
layer overlaps share that lower bound. Foreground contrast is checked against that
conservative bound, replacing the former rectangular white veils. Gray covers stay
neutral; the palette test still exercises 2000 mixed palettes and vivid-cover cases.

The gradients travel up to 18% of width and 22% of height, with modest elliptical
shape changes, but each random transition takes 5–10 seconds. Quintic easing
keeps direction changes smooth. The existing PCM spectrum adds only a subtle
brightness envelope. No extra audio sampling or live blur pass is added.

Pause/background cancels the clock without resetting the light positions. Resume
starts a fresh timestamp baseline, and delayed frames are bounded. Only the
background draw node reads the animation clock; gradients, circles and radii are
cached, with Canvas transforms moving their centers. Layout, record rotation and
progress rendering remain independent. Shader brushes are not recreated per frame.

After installing the CI APK, verify 4:3 and 16:9 screens over several minutes:
continuous soft gradients at every edge/corner (especially bottom-left), no white
panel boundaries, slow organic drift, readable text/header, pause/resume continuity,
no jump after returning from desktop, and smooth record rotation. Mathematical
palette tests do not replace these Android rendering checks.

## Square displays

Ratios below 1.3 use compact margins and width-based title sizing (18–28sp).
The two columns remain balanced. Transport buttons, their glyphs and gaps scale
uniformly to the details column width; square screens cap that scale at 85%.
The transport row opts out of Material's 48dp minimum layout size so it cannot
force the scaled buttons beyond the column. Verify 320/360/480/600dp square screens
with a two-line title and all three controls fully visible. The 4x ambient-light
speed is retained.

Near-identical extracted light colors use white as the secondary gradient stop.
Single-hue and gray covers thus retain a gentle color-to-white wash; pure white
stays white, and genuinely distinct extracted hues are retained.

Every new random target is at least 20% of the shorter screen edge from its
predecessor. Bounded rejection sampling has an opposite-corner fallback, so
near-identical targets and unbounded retry loops cannot occur. Easing, pause
freezing, the continuous gradient surface and the square control layout remain.

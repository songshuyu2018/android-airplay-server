package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.Context
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.LightTuning
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Size
import io.github.jqssun.airplay.audio.PlayerPalette
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*
import kotlin.random.Random

private data class PlayerColors(val base: Color, val glow: Color, val secondaryGlow: Color,
    val ink: Color, val body: Color, val accent: Color)
private fun PlayerPalette.colors() = PlayerColors(Color(base), Color(glow), Color(secondaryGlow), Color(ink), Color(body), Color(accent))
private val DefaultPlayerColors = PlayerPalette.fromPixels(intArrayOf()).colors()
private fun coverColors(bitmap: Bitmap?): PlayerColors {
    if (bitmap == null) return DefaultPlayerColors
    val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
    val pixels = IntArray(1024)
    small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
    if (small !== bitmap) small.recycle()
    return PlayerPalette.fromPixels(pixels).colors()
}

@Composable
private fun animatedColors(target: PlayerColors): PlayerColors {
    val base by animateColorAsState(target.base, tween(600), label = "player base")
    val glow by animateColorAsState(target.glow, tween(600), label = "player glow")
    val secondary by animateColorAsState(target.secondaryGlow, tween(600), label = "player secondary")
    val ink by animateColorAsState(target.ink, tween(600), label = "player ink")
    val body by animateColorAsState(target.body, tween(600), label = "player body")
    val accent by animateColorAsState(target.accent, tween(600), label = "player accent")
    return PlayerColors(base, glow, secondary, ink, body, accent)
}

@Composable
fun PlayingScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val track by viewModel.trackInfo.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val reportedPlaying by viewModel.audioPlaying.collectAsState()
    val rotationEnabled by viewModel.pref(Prefs.PLAYER_RECORD_ROTATION).collectAsState()
    val previewRequested by viewModel.pref(Prefs.PLAYER_THEME_PREVIEW).collectAsState()
    val auroraBeats by viewModel.pref(Prefs.PLAYER_AURORA_BEATS).collectAsState()
    val materialCache by viewModel.pref(Prefs.PLAYER_AURORA_MATERIAL_CACHE).collectAsState()
    val auroraSamples by viewModel.pref(Prefs.PLAYER_AURORA_SAMPLES).collectAsState()
    val auroraFps by viewModel.pref(Prefs.PLAYER_AURORA_FPS).collectAsState()
    val auroraQuality by viewModel.pref(Prefs.PLAYER_AURORA_QUALITY).collectAsState()
    val auroraEnabled by viewModel.pref(Prefs.PLAYER_AURORA).collectAsState()
    var auroraFailed by remember { mutableStateOf(false) }
    LaunchedEffect(auroraEnabled) { auroraFailed = false }
    val lightTuningEnabled by viewModel.pref(Prefs.PLAYER_LIGHT_TUNING).collectAsState()
    val controlsReady by viewModel.audioControlsReady.collectAsState()
    val connected = connections > 0 && audioOnly
    val previewPlaying = PlayerThemePreview.enabled(previewRequested, auroraEnabled, connections)
    val latestPreview by rememberUpdatedState(previewPlaying)
    val previewValues = remember { FloatArray(49) }
    val previewEpoch = remember { System.nanoTime() }
    LaunchedEffect(previewPlaying) { if (previewPlaying) viewModel.audioSpectrum(false) }
    val playing = previewPlaying || (connected && reportedPlaying)
    val latestPlaying by rememberUpdatedState(playing)
    val activity = LocalContext.current as? Activity
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    val context = LocalContext.current
    val lightPrefs = remember(context) { context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    var lightTuning by remember(lightPrefs) { mutableStateOf(LightTuning.load(lightPrefs)) }
    val latestTuning by rememberUpdatedState(lightTuning)
    var tuningOpen by remember { mutableStateOf(false) }
    LaunchedEffect(lightTuningEnabled) { if (!lightTuningEnabled) tuningOpen = false }
    DisposableEffect(lightPrefs) { onDispose { latestTuning.save(lightPrefs) } }
    val angle = remember { mutableFloatStateOf(0f) }
    val spectrum = remember { mutableStateOf(FloatArray(48)) }
    val lightEnergy = remember { mutableFloatStateOf(0f) }
    val lightBeat = remember { mutableFloatStateOf(0f) }
    val displayCover by produceState<ImageBitmap?>(null, track.coverArt) {
        value = withContext(Dispatchers.Default) {
            track.coverArt?.let { source ->
                val scale = minOf(1f, 1024f / maxOf(source.width, source.height))
                val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), true) else source
                bitmap.asImageBitmap()
            }
        }
    }
    var targetColors by remember { mutableStateOf(DefaultPlayerColors) }
    val colors = animatedColors(if (auroraEnabled && !auroraFailed) PlayerColors(
        Color(0xFF010405), Color(0xFF123D31), Color(0xFF17152A),
        Color(0xFFF1F5F3), Color(0xFFC2CECA), Color(0xFFC2DECF)) else targetColors)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(track.coverArt) { targetColors = withContext(Dispatchers.Default) { coverColors(track.coverArt) } }
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (previous != null) activity?.requestedOrientation = previous
            viewModel.audioSpectrum(false)
        }
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(controlsReady) { if (controlsReady) playFocus.requestFocusUntilLanded() }
    // Rotation only changes a hardware layer property. No FFT, allocation or layout
    // work is performed in its vsync callback; pausing retains the current angle.
    LaunchedEffect(playing, lifecycle, rotationEnabled) {
        if (playing && rotationEnabled) lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            while (isActive) withFrameNanos { now ->
                if (previous != 0L) angle.floatValue =
                    (angle.floatValue + (now - previous) / 1e9f * 12f) % 360f
                previous = now
            }
        }
    }
    // Native PCM history is analysed at 256-sample hops, independent of display
    // cadence. Keep JNI on main so receiver teardown cannot invalidate its handle.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            try {
                while (isActive) withFrameNanos { now ->
                    val dt = if (previous == 0L) 1f / 60f else
                        ((now - previous) / 1e9f).coerceIn(0f, .1f)
                    previous = now
                    val values = if (latestPreview)
                        PlayerThemePreview.sample(((now - previewEpoch) / 1e9).toFloat(), previewValues)
                    else viewModel.audioSpectrum(latestPlaying, latestTuning)
                    if (latestPlaying && values.isNotEmpty()) {
                        var sum = 0f
                        for (i in 0 until minOf(16, values.size)) {
                            val band = values[i]
                            if (band.isFinite()) sum += band * band
                        }
                        val target = sqrt(sum / minOf(16, values.size)).coerceIn(0f, 1f)
                        lightEnergy.floatValue += (target - lightEnergy.floatValue) *
                            (1f - exp(-dt / .8f))
                    }
                    // Native timestamped envelope already has instantaneous attack
                    // and a soft release. Do not filter its attack a second time.
                    lightBeat.floatValue = if (latestPlaying)
                        values.getOrElse(48) { 0f }.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
                        else 0f
                    val old = spectrum.value
                    spectrum.value = FloatArray(48) { i ->
                        val target = if (latestPlaying) values.getOrElse(i) { 0f } else 0f
                        val follow = 1f - exp(-dt / (if (target > old[i]) .025f else .16f))
                        old[i] + (target - old[i]) * follow
                    }
                }
            } finally {
                lightBeat.floatValue = 0f
                viewModel.audioSpectrum(false)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.base)) {
        val square = maxWidth < maxHeight * 1.3f
        val compact = maxHeight < 440.dp || square
        val edge = if (square) 16.dp else if (compact) 20.dp else 40.dp
        val topGap = if (compact) 56.dp else 88.dp
        val columnGap = if (square) 16.dp else if (compact) 24.dp else 52.dp
        val contentWidth = (maxWidth - edge * 2 - columnGap).coerceAtLeast(0.dp)
        val detailsWidth = contentWidth / 2
        // Scale the whole transport group, including icons and spacing, to fit.
        val naturalControlWidth = if (compact) 192f else 212f
        val controlScale = minOf(1f, detailsWidth.value / naturalControlWidth,
            if (square) .85f else 1f).coerceAtLeast(.01f)
        val controlGap = (if (compact) 16.dp else 26.dp) * controlScale
        val titleSize = minOf(if (square) 28f else if (compact) 27f else 40f,
            detailsWidth.value * .12f).coerceAtLeast(18f).sp
        if (auroraEnabled && !auroraFailed) AuroraBackdrop(
            playing, energy = { lightEnergy.floatValue }, beat = { lightBeat.floatValue },
            spectrum = { spectrum.value }, quality = auroraQuality, frameRate = auroraFps, sampleCount = auroraSamples, materialCache = materialCache, beatThreshold = auroraBeats, modifier = Modifier.fillMaxSize(),
            onFailure = { auroraFailed = true })
        else PlayerBackdrop(colors, playing, lifecycle, energy = { lightEnergy.floatValue },
            beat = { lightBeat.floatValue }, tuning = lightTuning)
        Row(Modifier.fillMaxSize().padding(horizontal = edge).padding(top = topGap, bottom = if (compact) 18.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val discSize = minOf(maxWidth, maxHeight) * .80f
                RecordSpectrum(displayCover, colors, { angle.floatValue }, { spectrum.value }, Modifier.size(discSize))
            }
            Spacer(Modifier.width(columnGap))
            Column(Modifier.width(detailsWidth).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                if (!compact) {
                    Text("NOW PLAYING", color = colors.body, fontSize = 11.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(18.dp))
                }
                Text(track.title.ifBlank { stringResource(R.string.player_waiting) }, color = colors.ink, fontSize = titleSize,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = titleSize * 1.2f)
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                Text(track.artist.ifBlank { if (connected) stringResource(R.string.player_unknown_artist) else "AirPlay" }, color = colors.body, fontSize = if (compact) 15.sp else 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(track.album.ifBlank { if (connected) stringResource(R.string.player_unknown_album) else "" }, color = colors.body, fontSize = if (compact) 12.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(if (compact) 20.dp else 34.dp))
                PlayerProgress(viewModel, colors.body)
                Spacer(Modifier.height(if (compact) 10.dp else 20.dp))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(controlGap)) {
                    PlayerButton(TransportGlyph.Previous, stringResource(R.string.player_previous), controlsReady && connected, colors.ink, scale = controlScale, onClick = viewModel::previousAudioTrack)
                    PlayerButton(if (playing) TransportGlyph.Pause else TransportGlyph.Play,
                        stringResource(if (playing) R.string.player_pause else R.string.player_play), controlsReady && connected, colors.ink,
                        primary = true, scale = controlScale, onPrimary = colors.base, modifier = Modifier.focusRequester(playFocus), onClick = viewModel::toggleAudioPlayback)
                    PlayerButton(TransportGlyph.Next, stringResource(R.string.player_next), controlsReady && connected, colors.ink, scale = controlScale, onClick = viewModel::nextAudioTrack)
                }
                }
            }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)).padding(horizontal = edge, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRing(color = colors.accent)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_back), tint = colors.accent)
            }
            Text("AIRPLAY · 沉浸聆听", color = colors.body, fontSize = 12.sp, fontWeight = FontWeight.Light, letterSpacing = 1.5.sp)
        }
        if (lightTuningEnabled) LightTuningOverlay(
            tuning = lightTuning, raw = { lightBeat.floatValue }, open = tuningOpen,
            onOpen = { tuningOpen = it }, onChange = { lightTuning = it },
            onSave = { lightTuning.save(lightPrefs) },
            onReset = { lightTuning = LightTuning(); lightTuning.save(lightPrefs) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .widthIn(max = minOf(380.dp, maxWidth - 24.dp))
                .heightIn(max = maxHeight * .85f)
        )
    }
}

private data class LightKey(
    val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val opacity: Float
)

/** Bounded random travel with a minimum distance between successive targets. */
private class LightDrift {
    private companion object {
        const val X_REACH = .18f
        const val Y_REACH = .22f
        const val MIN_DISTANCE_SQUARED = .20f * .20f
        const val FULL_TURN = 6.2831855f
        const val ZOOM_PERIOD_SECONDS = 3f
    }
    private fun key() = LightKey(
        Random.nextFloat() * (2f * X_REACH) - X_REACH,
        Random.nextFloat() * (2f * Y_REACH) - Y_REACH,
        .88f + Random.nextFloat() * .24f,
        .88f + Random.nextFloat() * .24f,
        .9f + Random.nextFloat() * .1f
    )
    private fun nextKey(previous: LightKey): LightKey {
        repeat(24) {
            val candidate = key()
            val dx = candidate.x - previous.x
            val dy = candidate.y - previous.y
            // Normalized screen coordinates guarantee >=20% of the shorter
            // screen edge in actual pixels, including non-square layouts.
            if (dx * dx + dy * dy >= MIN_DISTANCE_SQUARED) return candidate
        }
        // A bounded fallback avoids an unlucky retry loop and still guarantees
        // the minimum distance, even when the previous target is near center.
        return key().copy(
            x = if (previous.x >= 0f) -X_REACH else X_REACH,
            y = if (previous.y >= 0f) -Y_REACH else Y_REACH
        )
    }
    private var zoomPhase = Random.nextFloat() * FULL_TURN
    val zoom: Float get() = 1f + .12f * sin(zoomPhase)
    private var from = key()
    private var to = nextKey(from)
    private var elapsed = 0f
    private var duration = 2f + Random.nextFloat() * 3f
    var x = from.x
        private set
    var y = from.y
        private set
    var scaleX = from.scaleX
        private set
    var scaleY = from.scaleY
        private set
    var opacity = from.opacity
        private set

    fun advance(seconds: Float) {
        // Independent smooth 3-second breathing cycle; it freezes with playback.
        zoomPhase = (zoomPhase + seconds * FULL_TURN / ZOOM_PERIOD_SECONDS) % FULL_TURN
        elapsed += seconds
        while (elapsed >= duration) {
            elapsed -= duration
            from = to
            to = nextKey(from)
            duration = 2f + Random.nextFloat() * 3f
        }
        val t = (elapsed / duration).coerceIn(0f, 1f)
        // Zero velocity and acceleration at each boundary: no abrupt direction changes.
        val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
        x = from.x + (to.x - from.x) * eased
        y = from.y + (to.y - from.y) * eased
        scaleX = from.scaleX + (to.scaleX - from.scaleX) * eased
        scaleY = from.scaleY + (to.scaleY - from.scaleY) * eased
        opacity = from.opacity + (to.opacity - from.opacity) * eased
    }
}

@Composable
private fun PlayerBackdrop(colors: PlayerColors, playing: Boolean, lifecycle: Lifecycle?,
    energy: () -> Float, beat: () -> Float, tuning: LightTuning) {
    val latestTuning by rememberUpdatedState(tuning)
    val latestBeat by rememberUpdatedState(beat)
    val lights = remember { Array(3) { LightDrift() } }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, lifecycle) {
        if (playing) lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            var speed = 1f
            while (isActive) withFrameNanos { now ->
                if (previous != 0L) {
                    // Lifecycle/pause cancels this clock. Bound any delayed frame too,
                    // so resuming or a busy device never jumps the light across the screen.
                    val seconds = ((now - previous) / 1e9f).coerceIn(0f, .1f)
                    val response = latestTuning.response(latestBeat())
                    val targetSpeed = 1f + response * latestTuning.motionBoost / 100f
                    val timeConstant = if (targetSpeed > speed) .05f else latestTuning.release / 1000f
                    speed += (targetSpeed - speed) * (1f - exp(-seconds / timeConstant))
                    // Advance the SAME continuous path; never choose a new position
                    // on a hit. Cap delayed-frame travel to avoid visible jumps.
                    val travel = (seconds * speed).coerceIn(0f, .20f)
                    for (light in lights) light.advance(travel)
                    frame.longValue++
                }
                previous = now
            }
        }
    }
    // One uninterrupted canvas, as in the original background. Move the gradients
    // themselves, never a rectangular color layer or a separate reading panel.
    Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
        val width = size.width
        val height = size.height
        val base = Brush.linearGradient(listOf(colors.base, colors.secondaryGlow, colors.base),
            end = Offset(width, height))
        val centers = arrayOf(
            Offset(width * .18f, height * .34f),
            Offset(width * .92f, height * .12f),
            Offset(width * .64f, height * 1.1f)
        )
        val radii = floatArrayOf(width * .75f, width * .58f, width * .64f)
        val shades = arrayOf(colors.glow.copy(alpha = .95f),
            colors.secondaryGlow.copy(alpha = .85f), colors.glow.copy(alpha = .48f))
        val gradients = Array(3) { index ->
            Brush.radialGradient(listOf(shades[index], shades[index].copy(alpha = 0f)),
                center = Offset.Zero, radius = radii[index])
        }
        val leftRestColor = Color(
            red = colors.base.red * .55f + colors.glow.red * .45f,
            green = colors.base.green * .55f + colors.glow.green * .45f,
            blue = colors.base.blue * .55f + colors.glow.blue * .45f)
        val leftRestGradient = Brush.radialGradient(
            listOf(leftRestColor.copy(alpha = .95f), leftRestColor.copy(alpha = 0f)),
            center = Offset.Zero, radius = radii[0])
        // Preserve the ambient gradients and bounded paths. Audio modulates only
        // their clock speed, never the targets, shape, radius or palette.
        val drawOrder = intArrayOf(1, 2, 0)
        onDrawBehind {
            // Only this draw node observes the clock; layout and the record stay
            // independent. Both left color variants are cached, not rebuilt per beat.
            frame.longValue
            drawRect(base)
            val breath = .94f + energy() * .06f
            for (index in drawOrder) {
                val light = lights[index]
                val center = centers[index]
                val x = center.x + light.x * width
                val y = center.y + light.y * height
                val sx = light.scaleX * light.zoom
                val sy = light.scaleY * light.zoom
                withTransform({
                    translate(x, y)
                    scale(sx, sy, pivot = Offset.Zero)
                }) {
                    if (index == 0) {
                        // Same pale wash and micro-motion; hits gently accelerate
                        // the continuous drift instead of flashing a new overlay.
                        drawCircle(leftRestGradient, radii[0], center = Offset.Zero,
                            alpha = light.opacity * breath)
                    } else {
                        drawCircle(gradients[index], radii[index], center = Offset.Zero,
                            alpha = light.opacity * breath)
                    }
                }
            }
        }
    })
}

@Composable
private fun RecordSpectrum(cover: ImageBitmap?, colors: PlayerColors, angle: () -> Float,
    spectrum: () -> FloatArray, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // Cache all trigonometry and shaders. Reading the bands only in the draw
        // phase invalidates this spectrum layer, not the rotating record.
        Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.minDimension
            val directions = Array(96) { i ->
                val radians = (i / 96f * 2 * PI - PI / 2).toFloat()
                Offset(cos(radians), sin(radians))
            }
            val starts = Array(96) { center + directions[it] * (unit * .435f) }
            val width = (unit * .0055f).coerceAtLeast(1f)
            onDrawBehind {
                val bands = spectrum()
                for (i in 0 until 96) {
                    val amplitude = bands[if (i < 48) i else 95 - i]
                    val length = unit * (.009f + .052f * amplitude)
                    drawLine(colors.accent.copy(alpha = .25f + amplitude * .7f), starts[i],
                        starts[i] + directions[i] * length, strokeWidth = width, cap = StrokeCap.Round)
                }
            }
        })
        // Keep the shadow stationary and rasterize the disc once into its own
        // layer. Each vsync updates only rotationZ on that cached layer.
        Box(Modifier.fillMaxSize(.77f).shadow(24.dp, CircleShape)) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                rotationZ = angle()
                compositingStrategy = CompositingStrategy.Offscreen
                shape = CircleShape
                clip = true
            }.background(Color(0xFF17151D)), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().drawWithCache {
                    val radius = size.minDimension / 2
                    val grooves = Array(45) { radius * (.34f + it * .0145f) }
                    val highlight = Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .13f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = .08f), Color.Transparent))
                    val stroke = Stroke(1f)
                    onDrawBehind {
                        for (i in grooves.indices) drawCircle(Color.White.copy(alpha = if (i % 3 == 0) .06f else .025f), grooves[i], style = stroke)
                        drawCircle(highlight)
                    }
                })
                if (cover != null) Image(cover, "Album artwork", Modifier.fillMaxSize(.59f).clip(CircleShape), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
                else Box(Modifier.fillMaxSize(.59f).clip(CircleShape).background(Brush.linearGradient(listOf(colors.glow, colors.base))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(.4f), tint = colors.accent)
                }
            }
        }
    }
}

// Round the contour itself, rather than only the stroke's outer joins.
private fun roundedTriangle(vertices: List<Offset>): Path = Path().apply {
    for (i in vertices.indices) {
        val corner = vertices[i]
        val entry = corner + (vertices[(i + 2) % 3] - corner) * .18f
        val exit = corner + (vertices[(i + 1) % 3] - corner) * .18f
        if (i == 0) moveTo(entry.x, entry.y) else lineTo(entry.x, entry.y)
        quadraticBezierTo(corner.x, corner.y, exit.x, exit.y)
    }
    close()
}

private enum class TransportGlyph { Previous, Play, Pause, Next }

@Composable
private fun PlayerButton(icon: TransportGlyph, label: String, enabled: Boolean, accent: Color,
    primary: Boolean = false, scale: Float = 1f, onPrimary: Color = Color.White,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size((if (primary) 64.dp else 48.dp) * scale)
            .focusRing(color = if (primary) onPrimary else accent)
            .background(if (primary) accent.copy(alpha = if (enabled) 1f else .3f) else Color.Transparent, CircleShape)
            .semantics { contentDescription = label }) {
        Canvas(Modifier.size((if (primary) 26.dp else 24.dp) * scale)) {
            val u = size.minDimension / 24f
            val color = (if (primary) onPrimary else accent).copy(alpha = if (enabled) 1f else .35f)
            fun point(x: Float, y: Float) = Offset(x * u, y * u)
            val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                TransportGlyph.Play -> {
                    val triangle = roundedTriangle(listOf(point(8f, 4f), point(20f, 12f), point(8f, 20f)))
                    drawPath(triangle, color, style = stroke)
                }
                TransportGlyph.Pause -> {
                    for (x in listOf(6f, 14f)) drawRoundRect(color, point(x, 4f), Size(4*u, 16*u), androidx.compose.ui.geometry.CornerRadius(u, u), style = stroke)
                }
                TransportGlyph.Previous, TransportGlyph.Next -> {
                    val previous = icon == TransportGlyph.Previous
                    val triangle = roundedTriangle(listOf(
                        point(if (previous) 19f else 5f, 4f),
                        point(if (previous) 7f else 17f, 12f),
                        point(if (previous) 19f else 5f, 20f)))
                    drawPath(triangle, color, style = stroke)
                    val shaft = if (previous) 4f else 20f
                    drawLine(color, point(shaft, 4f), point(shaft, 20f), strokeWidth = 1.8f*u, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun PlayerProgress(viewModel: MainViewModel, accent: Color) {
    // Progress ticks only recompose this small subtree, not the record/backdrop.
    val position by viewModel.audioPositionMs.collectAsState()
    val duration by viewModel.audioDurationMs.collectAsState()
    val progress = if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = accent.copy(alpha = .15f))
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(playerTime(position), color = accent, fontSize = 11.sp)
        Text(if (duration > 0) playerTime(duration) else "--:--", color = accent, fontSize = 11.sp)
    }
}

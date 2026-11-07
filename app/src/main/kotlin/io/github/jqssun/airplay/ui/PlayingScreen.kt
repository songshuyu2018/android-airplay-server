package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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

private data class PlayerColors(val base: Color, val glow: Color, val accent: Color)
private val DefaultPlayerColors = PlayerColors(Color(0xFF17131F), Color(0xFF60405D), Color(0xFFF1BCDA))
private fun coverColors(bitmap: Bitmap?): PlayerColors {
    if (bitmap == null) return DefaultPlayerColors
    val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
    val buckets = Array(24) { FloatArray(4) }
    val hsv = FloatArray(3)
    for (y in 0 until 16) for (x in 0 until 16) {
        AndroidColor.colorToHSV(small.getPixel(x, y), hsv)
        if (hsv[2] > .15f && hsv[2] < .95f && hsv[1] > .12f) {
            val bucket = buckets[(hsv[0] / 15).toInt().coerceIn(0, 23)]
            bucket[0] += hsv[0]; bucket[1] += hsv[1]; bucket[2] += hsv[2]; bucket[3]++
        }
    }
    if (small !== bitmap) small.recycle()
    val best = buckets.maxByOrNull { it[3] } ?: return DefaultPlayerColors
    if (best[3] == 0f) return DefaultPlayerColors
    val hue = best[0] / best[3]
    fun color(s: Float, v: Float) = Color(AndroidColor.HSVToColor(floatArrayOf(hue, s, v)))
    return PlayerColors(color(.32f, .10f), color(.52f, .37f), color(.23f, .97f))
}

@Composable
fun PlayingScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val track by viewModel.trackInfo.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val reportedPlaying by viewModel.audioPlaying.collectAsState()
    val controlsReady by viewModel.audioControlsReady.collectAsState()
    val position by viewModel.audioPositionMs.collectAsState()
    val duration by viewModel.audioDurationMs.collectAsState()
    val connected = connections > 0 && audioOnly
    val playing = connected && reportedPlaying
    val latestPlaying by rememberUpdatedState(playing)
    val activity = LocalContext.current as? Activity
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    val angle = remember { mutableFloatStateOf(0f) }
    val spectrum = remember { mutableStateOf(FloatArray(48)) }
    var colors by remember { mutableStateOf(DefaultPlayerColors) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(track.coverArt) { colors = withContext(Dispatchers.Default) { coverColors(track.coverArt) } }
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
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            var lastSpectrum = 0L
            try {
                while (isActive) {
                    withFrameNanos { now ->
                        if (latestPlaying && previous != 0L) {
                            angle.floatValue = (angle.floatValue + ((now - previous) / 1e9f).coerceAtMost(.1f) * 12f) % 360f
                        }
                        previous = now
                        if (now - lastSpectrum >= 33_000_000L) {
                            val values = viewModel.audioSpectrum(latestPlaying)
                            val old = spectrum.value
                            spectrum.value = FloatArray(48) { i ->
                                val target = if (latestPlaying) values.getOrElse(i) { 0f } else 0f
                                old[i] + (target - old[i]) * if (target > old[i]) .65f else .18f
                            }
                            lastSpectrum = now
                        }
                    }
                }
            } finally { viewModel.audioSpectrum(false) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.base)) {
        val compact = maxHeight < 440.dp
        val edge = if (compact) 20.dp else 40.dp
        val topGap = if (compact) 56.dp else 88.dp
        val cover = remember(track.coverArt) { track.coverArt?.asImageBitmap() }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(colors.glow.copy(alpha = .75f), colors.base), center = Offset(size.width * .22f, size.height * .42f), radius = size.width * .7f))
            drawRect(Brush.radialGradient(listOf(colors.accent.copy(alpha = .12f), Color.Transparent), center = Offset(size.width * .85f, 0f), radius = size.width * .6f))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = edge).padding(top = topGap, bottom = if (compact) 18.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val discSize = minOf(maxWidth, maxHeight)
                RecordSpectrum(cover, colors, { angle.floatValue }, { spectrum.value }, Modifier.size(discSize))
            }
            Spacer(Modifier.width(if (compact) 24.dp else 52.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                if (!compact) {
                    Text("NOW PLAYING", color = colors.accent.copy(alpha = .65f), fontSize = 11.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(18.dp))
                }
                Text(track.title.ifBlank { stringResource(R.string.player_waiting) }, color = Color(0xFFFFF4FC), fontSize = if (compact) 27.sp else 40.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (compact) 33.sp else 48.sp)
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                Text(track.artist.ifBlank { if (connected) stringResource(R.string.player_unknown_artist) else "AirPlay" }, color = colors.accent, fontSize = if (compact) 15.sp else 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(track.album.ifBlank { if (connected) stringResource(R.string.player_unknown_album) else "" }, color = colors.accent.copy(alpha = .72f), fontSize = if (compact) 12.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(if (compact) 20.dp else 34.dp))
                PlayerProgress(position, duration, colors.accent)
                Spacer(Modifier.height(if (compact) 10.dp else 20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 26.dp)) {
                    PlayerButton(Icons.Default.SkipPrevious, stringResource(R.string.player_previous), controlsReady && connected, colors.accent, onClick = viewModel::previousAudioTrack)
                    PlayerButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (playing) R.string.player_pause else R.string.player_play), controlsReady && connected, colors.accent,
                        primary = true, modifier = Modifier.focusRequester(playFocus), onClick = viewModel::toggleAudioPlayback)
                    PlayerButton(Icons.Default.SkipNext, stringResource(R.string.player_next), controlsReady && connected, colors.accent, onClick = viewModel::nextAudioTrack)
                }
            }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)).padding(horizontal = edge, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRing(color = colors.accent)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_back), tint = colors.accent)
            }
            Text(stringResource(if (!connected) R.string.player_waiting else if (playing) R.string.player_playing else R.string.player_paused), color = colors.accent.copy(alpha = .8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecordSpectrum(cover: ImageBitmap?, colors: PlayerColors, angle: () -> Float, spectrum: () -> FloatArray, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.minDimension
            drawCircle(Brush.radialGradient(listOf(colors.accent.copy(alpha = .08f), Color.Transparent), center, unit * .5f), unit * .5f, center)
            val bands = spectrum()
            for (i in 0 until 96) {
                val radians = (i / 96f * 2 * PI - PI / 2).toFloat()
                val amplitude = bands[if (i < 48) i else 95 - i]
                val radius = unit * .435f
                val length = unit * (.009f + .052f * amplitude)
                val direction = Offset(cos(radians), sin(radians))
                drawLine(colors.accent.copy(alpha = .25f + amplitude * .7f), center + direction * radius,
                    center + direction * (radius + length), strokeWidth = (unit * .0055f).coerceAtLeast(1f), cap = StrokeCap.Round)
            }
        }
        Box(Modifier.fillMaxSize(.77f).shadow(24.dp, CircleShape).graphicsLayer { rotationZ = angle() }.clip(CircleShape).background(Color(0xFF17151D)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                for (i in 0..44) drawCircle(Color.White.copy(alpha = if (i % 3 == 0) .06f else .025f), radius * (.34f + i * .0145f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .13f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = .08f), Color.Transparent)))
            }
            if (cover != null) Image(cover, "Album artwork", Modifier.fillMaxSize(.59f).clip(CircleShape), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(.59f).clip(CircleShape).background(Brush.linearGradient(listOf(colors.glow, colors.base))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(.4f), tint = colors.accent)
            }
            Box(Modifier.fillMaxSize(.035f).clip(CircleShape).background(Color(0xFF17151D)))
        }
    }
}

@Composable
private fun PlayerButton(icon: ImageVector, label: String, enabled: Boolean, accent: Color,
    primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(if (primary) 64.dp else 48.dp).focusRing(color = Color.White)
            .background(if (primary) accent.copy(alpha = if (enabled) 1f else .3f) else Color.Transparent, CircleShape)) {
        Icon(icon, label, Modifier.size(if (primary) 30.dp else 27.dp), tint =
            if (primary) Color(0xFF201522) else accent.copy(alpha = if (enabled) 1f else .3f))
    }
}

private fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun PlayerProgress(position: Long, duration: Long, accent: Color) {
    val progress = if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = accent.copy(alpha = .15f))
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(playerTime(position), color = accent.copy(alpha = .75f), fontSize = 11.sp)
        Text(if (duration > 0) playerTime(duration) else "--:--", color = accent.copy(alpha = .75f), fontSize = 11.sp)
    }
}

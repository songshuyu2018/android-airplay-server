package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import io.github.jqssun.airplay.R
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Real, interactive application-overlay window; never uses an invisible launch trampoline. */
class AudioPlayerOverlay(private val service: AirPlayService) {
    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var current: Window? = null

    fun show(): Boolean {
        if (current != null) return true
        if (!Settings.canDrawOverlays(service)) {
            Log.w(TAG, "Overlay permission unavailable; receiver and media notification remain active")
            return false
        }
        return try {
            val window = Window()
            current = window
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                setTitle("AirPlay music player")
            }
            manager.addView(window.view, params)
            window.owner.start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                window.backDispatcher = window.view.findOnBackInvokedDispatcher()
                window.backDispatcher?.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, window.backCallback)
            }
            window.scope.launch { while (isActive) { window.source.poll(); delay(200) } }
            Log.i(TAG, "Player overlay shown")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Cannot display player overlay", error)
            hide()
            false
        }
    }

    fun hide() {
        val window = current ?: return
        current = null
        window.scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.backDispatcher?.unregisterOnBackInvokedCallback(window.backCallback)
        }
        window.view.disposeComposition()
        window.owner.destroy()
        if (window.view.isAttachedToWindow) {
            try { manager.removeViewImmediate(window.view) }
            catch (error: Exception) { Log.w(TAG, "Cannot detach overlay", error) }
        }
        Log.i(TAG, "Player overlay removed")
    }

    private inner class Window {
        var backDispatcher: OnBackInvokedDispatcher? = null
        val backCallback by lazy { OnBackInvokedCallback { hide() } }
        val owner = WindowOwner()
        val source = ServicePlayerSource(service)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AirPlayTheme { OverlayPlayer(source, onDismiss = this@AudioPlayerOverlay::hide) } }
        }
    }
    private class WindowOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        fun start() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun destroy() {
            if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
    private companion object { const val TAG = "AirPlayOverlay" }
}

@Composable
private fun OverlayPlayer(source: PlayerSource, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onDismiss()
            true
        } else false
    }.focusable(), contentAlignment = Alignment.Center) {
        // Some launchers lock portrait even for an overlay requesting landscape.
        // Rotate only this surface, without changing the underlying app's settings.
        if (maxHeight > maxWidth) {
            Box(Modifier.requiredSize(maxHeight, maxWidth).graphicsLayer { rotationZ = 90f }) {
                PlayerContent(source, onBack = onDismiss, backDescription = stringResource(R.string.player_close))
            }
        } else PlayerContent(source, onBack = onDismiss, backDescription = stringResource(R.string.player_close))
    }
}

private class ServicePlayerSource(private val service: AirPlayService) : PlayerSource {
    override val trackInfo = service.trackInfo
    override val connectionCount = service.connectionCount
    override val audioOnly = service.audioOnly
    override val audioPlaying = service.playing
    override val audioDurationMs = service.durationMs
    private val ready = MutableStateFlow(false)
    override val audioControlsReady = ready.asStateFlow()
    private val position = MutableStateFlow(0L)
    override val audioPositionMs = position.asStateFlow()
    fun poll() {
        ready.value = service.dacpController?.ready == true
        position.value = service.currentPositionMs()
    }
    override fun toggleAudioPlayback() = service.togglePlayPause()
    override fun previousAudioTrack() { service.dacpController?.prevItem() }
    override fun nextAudioTrack() { service.dacpController?.nextItem() }
    override fun audioSpectrum(enabled: Boolean) = service.audioSpectrum(enabled)
}

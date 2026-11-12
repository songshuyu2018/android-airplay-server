package io.github.jqssun.airplay.ui

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.jqssun.airplay.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Background only: no input, playback commands, JNI or foreground drawing. */
@Composable
internal fun AuroraBackdrop(playing: Boolean, energy: () -> Float, beat: () -> Float,
    spectrum: () -> FloatArray, quality: Int, modifier: Modifier = Modifier, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val latestEnergy by rememberUpdatedState(energy)
    val latestBeat by rememberUpdatedState(beat)
    val latestSpectrum by rememberUpdatedState(spectrum)
    val latestFailure by rememberUpdatedState(onFailure)
    val supported = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.deviceConfigurationInfo?.reqGlEsVersion?.let { it >= 0x30000 } == true }
    if (!supported) { LaunchedEffect(Unit) { latestFailure() }; return }
    val view = remember(context) { AuroraView(context, { latestFailure() }) }
    AndroidView(factory = { view }, modifier = modifier, update = { it.setQuality(AuroraQuality.fromSetting(quality)) })
    DisposableEffect(view, lifecycle, playing) {
        view.input = { view.renderer.input(latestEnergy(), latestBeat(), latestSpectrum()) }
        fun sync() { view.setActive(playing && lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle?.addObserver(observer)
        sync()
        onDispose { lifecycle?.removeObserver(observer); view.setActive(false) }
    }
    DisposableEffect(view) { onDispose { view.input = null; view.setActive(false) } }
}

private class AuroraView(context: Context, failure: () -> Unit) : GLSurfaceView(context), Choreographer.FrameCallback {
    val renderer = AuroraRenderer(context) { post { failure() } }
    var input: (() -> Unit)? = null
    private var active = false
    private var suspended = false
    private var lastRequest = 0L
    private var quality = AuroraQuality.SMOOTH
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeBackground(w, h)
    }
    private fun resizeBackground(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val (bufferWidth, bufferHeight) = quality.bufferSize(w, h)
        // Only the SurfaceView buffer is reduced. Compose keeps full display resolution.
        holder.setFixedSize(bufferWidth, bufferHeight)
    }
    fun setQuality(value: AuroraQuality) {
        if (quality == value) return
        quality = value
        lastRequest = 0L
        resizeBackground(width, height)
        // Keep the GL context and animation clock when switching quality.
    }
    fun setActive(value: Boolean) {
        if (active == value && suspended == !value) return
        suspended = !value
        active = value
        renderer.running = value
        renderer.resetClock()
        Choreographer.getInstance().removeFrameCallback(this)
        if (value) { onResume(); lastRequest = 0; Choreographer.getInstance().postFrameCallback(this) }
        else onPause()
    }
    override fun doFrame(now: Long) {
        if (!active) return
        if (lastRequest == 0L || now - lastRequest >= quality.frameIntervalNanos - 1_000_000L) {
            input?.invoke()
            requestRender()
            lastRequest = now
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        active = false
        input = null
        super.onDetachedFromWindow()
    }
}

private class AuroraRenderer(private val context: Context, private val failure: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var running = false
    @Volatile private var previous = 0L
    private var time = 12.0
    private var program = 0
    private val values = FloatArray(5)
    private val snapshot = FloatArray(5)
    private val lock = Any()
    private var uniforms = IntArray(9)
    private val vertices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,3f,-1f,-1f,3f)); position(0)
    }
    fun resetClock() { previous = 0 }
    fun input(energy: Float, beat: Float, bands: FloatArray) {
        fun mean(start: Int, end: Int): Float {
            var sum = 0f
            for (i in start until minOf(end, bands.size)) { val v = bands[i]; if (v.isFinite()) sum += v * v }
            return sqrt(sum / (end - start)).coerceIn(0f, 1f)
        }
        synchronized(lock) {
            values[0] = mean(0,16); values[1] = mean(16,34); values[2] = mean(34,48)
            values[3] = if (energy.isFinite()) energy.coerceIn(0f,1f) else 0f
            values[4] = if (beat.isFinite()) beat.coerceIn(0f,1f) else 0f
        }
    }
    private fun shader(type: Int, source: String): Int {
        val id = GL.glCreateShader(type)
        GL.glShaderSource(id, source); GL.glCompileShader(id)
        val ok = IntArray(1); GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GL.glGetShaderInfoLog(id); GL.glDeleteShader(id); error(log) }
        return id
    }
    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        program = 0
        previous = 0
        GL.glClearColor(0f, 3f/255f, 5f/255f, 1f)
        try {
            val vertex = shader(GL.GL_VERTEX_SHADER, "#version 300 es\nin vec2 position;void main(){gl_Position=vec4(position,0.,1.);}")
            val fragment = try { shader(GL.GL_FRAGMENT_SHADER, context.resources.openRawResource(R.raw.aurora_fragment).bufferedReader().use { it.readText() }) }
                catch (e: Exception) { GL.glDeleteShader(vertex); throw e }
            val p = GL.glCreateProgram()
            GL.glAttachShader(p, vertex); GL.glAttachShader(p, fragment); GL.glLinkProgram(p)
            GL.glDeleteShader(vertex); GL.glDeleteShader(fragment)
            val ok = IntArray(1); GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GL.glGetProgramInfoLog(p); GL.glDeleteProgram(p); error(log) }
            program = p
            GL.glUseProgram(p)
            val position = GL.glGetAttribLocation(p, "position")
            val vbo = IntArray(1); GL.glGenBuffers(1, vbo, 0); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0])
            GL.glBufferData(GL.GL_ARRAY_BUFFER, 24, vertices, GL.GL_STATIC_DRAW)
            GL.glEnableVertexAttribArray(position); GL.glVertexAttribPointer(position, 2, GL.GL_FLOAT, false, 0, 0)
            uniforms = arrayOf("uResolution","uTime","uBass","uMid","uHigh","uEnergy","uBeat","uIntensity","uMotion").map { GL.glGetUniformLocation(p,it) }.toIntArray()
            GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)
        } catch (e: Exception) { Log.e("Aurora", "Cannot initialize background", e); failure() }
    }
    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GL.glViewport(0,0,width,height)
        if (program != 0) { GL.glUseProgram(program); GL.glUniform2f(uniforms[0],width.toFloat(),height.toFloat()) }
    }
    override fun onDrawFrame(unused: GL10?) {
        if (program == 0) { GL.glClear(GL.GL_COLOR_BUFFER_BIT); return }
        synchronized(lock) { values.copyInto(snapshot) }
        val now = System.nanoTime()
        if (running && previous != 0L) time += ((now-previous)/1e9).coerceIn(0.0,.1) * (1.0 + snapshot[0]*.75*.18 + snapshot[4]*.75*.12)
        previous = if (running) now else 0L
        GL.glUseProgram(program)
        GL.glUniform1f(uniforms[1],time.toFloat())
        for (i in 0..4) GL.glUniform1f(uniforms[i+2],snapshot[i])
        GL.glUniform1f(uniforms[7],.85f); GL.glUniform1f(uniforms[8],.75f)
        GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
    }
}

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
    spectrum: () -> FloatArray, quality: Int, frameRate: Int, beatThreshold: Int, modifier: Modifier = Modifier, onFailure: () -> Unit) {
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
    AndroidView(factory = { view }, modifier = modifier, update = {
        it.setQuality(AuroraQuality.fromSetting(quality))
        it.setFrameRate(frameRate)
        it.renderer.beatThreshold = beatThreshold
    })
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
    private var frameIntervalNanos = AuroraFrameRate.intervalNanos(20)
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
        // Full-resolution output keeps cached stars sharp; only aurora FBOs are scaled.
        renderer.quality = quality
        requestRender()
    }
    fun setQuality(value: AuroraQuality) {
        if (quality == value) return
        quality = value
        lastRequest = 0L
        resizeBackground(width, height)
        // Keep the GL context and animation clock when switching quality.
    }
    fun setFrameRate(value: Int) {
        val interval = AuroraFrameRate.intervalNanos(value)
        if (frameIntervalNanos == interval) return
        frameIntervalNanos = interval
        lastRequest = 0L
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
        if (lastRequest == 0L || now - lastRequest >= frameIntervalNanos - 1_000_000L) {
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
    @Volatile var quality = AuroraQuality.SMOOTH
    private var program = 0
    private var farProgram = 0
    private var starProgram = 0
    private var compositeProgram = 0
    private var farBrightnessUniform = -1
    private var starPulseUniform = -1
    @Volatile var beatThreshold = 16
    private val cycle = AuroraBeatCycle()
    private val lifeUniforms = IntArray(2)
    private val shapes = FloatArray(8)
    private val shapeUniforms = IntArray(2)
    private var starPulse = 0f
    @Volatile private var previousPulseTime = 0L
    private var screenWidth = 1
    private var screenHeight = 1
    private var cachedQuality: AuroraQuality? = null
    private var dirty = true
    private val textures = IntArray(3)
    private val framebuffers = IntArray(3)
    private var bufferWidth = 1
    private var bufferHeight = 1
    private val values = FloatArray(5)
    private val snapshot = FloatArray(5)
    private val lock = Any()
    private var uniforms = IntArray(9)
    private val vertices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,3f,-1f,-1f,3f)); position(0)
    }
    fun resetClock() { previous = 0; previousPulseTime = 0 }
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
    private fun link(fragmentSource: String): Int {
        val vertex = shader(GL.GL_VERTEX_SHADER, "#version 300 es\nlayout(location=0) in vec2 position;void main(){gl_Position=vec4(position,0.,1.);}")
        val fragment = try { shader(GL.GL_FRAGMENT_SHADER, fragmentSource) }
            catch (e: Exception) { GL.glDeleteShader(vertex); throw e }
        val p = GL.glCreateProgram()
        GL.glAttachShader(p, vertex); GL.glAttachShader(p, fragment); GL.glLinkProgram(p)
        GL.glDeleteShader(vertex); GL.glDeleteShader(fragment)
        val ok = IntArray(1); GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GL.glGetProgramInfoLog(p); GL.glDeleteProgram(p); error(log) }
        return p
    }
    private fun source(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        program = 0; previous = 0; dirty = true; cachedQuality = null
        textures.fill(0); framebuffers.fill(0)
        GL.glClearColor(0f, 0f, 0f, 1f)
        try {
            val volume = source(R.raw.aurora_fragment)
            program = link(volume)
            farProgram = link(volume.replace("#version 300 es", "#version 300 es\n#define FAR_LAYER"))
            starProgram = link(source(R.raw.aurora_stars))
            compositeProgram = link(source(R.raw.aurora_composite))
            farBrightnessUniform = GL.glGetUniformLocation(compositeProgram,"uFarBrightness")
            val vbo = IntArray(1); GL.glGenBuffers(1, vbo, 0); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0])
            GL.glBufferData(GL.GL_ARRAY_BUFFER, 24, vertices, GL.GL_STATIC_DRAW)
            GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0)
            uniforms = locations(program)
            shapeUniforms[0] = GL.glGetUniformLocation(program,"uShapeA")
            shapeUniforms[1] = GL.glGetUniformLocation(program,"uShapeB")
            starPulseUniform = GL.glGetUniformLocation(compositeProgram,"uStarPulse")
            lifeUniforms[0] = GL.glGetUniformLocation(program,"uBandAge")
            lifeUniforms[1] = GL.glGetUniformLocation(program,"uBandFade")
            GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_BLEND)
        } catch (e: Exception) { fail(e) }
    }
    private fun locations(p: Int) = arrayOf("uResolution","uTime","uBass","uMid","uHigh","uEnergy","uBeat","uIntensity","uMotion")
        .map { GL.glGetUniformLocation(p,it) }.toIntArray()
    private fun fail(e: Exception) {
        program = 0
        Log.e("Aurora", "Cannot render layered background", e); failure()
    }
    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        screenWidth = max(1, width); screenHeight = max(1, height); dirty = true
    }
    private fun target(index: Int, width: Int, height: Int) {
        GL.glBindTexture(GL.GL_TEXTURE_2D, textures[index])
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA8,width,height,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,framebuffers[index])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,textures[index],0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) { "Incomplete aurora layer" }
    }
    private fun volume(p: Int, u: IntArray, static: Boolean) {
        GL.glUseProgram(p)
        GL.glUniform2f(u[0],bufferWidth.toFloat(),bufferHeight.toFloat())
        GL.glUniform1f(u[1],if (static) 12f else time.toFloat())
        for (i in 0..4) GL.glUniform1f(u[i+2],if (static) 0f else snapshot[i])
        GL.glUniform1f(u[7],if (static) .34f else .85f)
        GL.glUniform1f(u[8],if (static) 0f else .75f)
        if (!static) {
            AuroraMotion.writeGenerations(cycle.generationA, cycle.generationB, shapes)
            GL.glUniform2f(lifeUniforms[0],(cycle.ageA/56).toFloat(),(cycle.ageB/56).toFloat())
            GL.glUniform2f(lifeUniforms[1],cycle.fadeA.toFloat(),cycle.fadeB.toFloat())
            GL.glUniform4fv(shapeUniforms[0],1,shapes,0)
            GL.glUniform4fv(shapeUniforms[1],1,shapes,4)
        }
        GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
    }
    private fun rebuildLayers(q: AuroraQuality) {
        GL.glDeleteTextures(3,textures,0); GL.glDeleteFramebuffers(3,framebuffers,0)
        GL.glGenTextures(3,textures,0); GL.glGenFramebuffers(3,framebuffers,0)
        val size = q.bufferSize(screenWidth, screenHeight)
        bufferWidth = size.first; bufferHeight = size.second
        target(0,bufferWidth,bufferHeight)
        target(1,bufferWidth,bufferHeight)
        GL.glViewport(0,0,bufferWidth,bufferHeight)
        volume(farProgram,locations(farProgram),true)
        target(2,screenWidth,screenHeight)
        GL.glViewport(0,0,screenWidth,screenHeight)
        GL.glUseProgram(starProgram)
        GL.glUniform2f(GL.glGetUniformLocation(starProgram,"uResolution"),screenWidth.toFloat(),screenHeight.toFloat())
        GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
        cachedQuality = q; dirty = false
    }
    override fun onDrawFrame(unused: GL10?) {
        if (program == 0) { GL.glClear(GL.GL_COLOR_BUFFER_BIT); return }
        try {
            synchronized(lock) { values.copyInto(snapshot) }
            val now = System.nanoTime()
            val elapsed = if (running && previous != 0L) ((now-previous)/1e9).coerceIn(0.0,.25) else 0.0
            time += elapsed * 2.0
            cycle.update(elapsed, snapshot[4], beatThreshold)
            previous = if (running) now else 0L
            val q = quality
            if (dirty || cachedQuality != q) rebuildLayers(q)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,framebuffers[0])
            GL.glViewport(0,0,bufferWidth,bufferHeight)
            volume(program,uniforms,false)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
            GL.glViewport(0,0,screenWidth,screenHeight)
            GL.glUseProgram(compositeProgram)
            // Cheap global opacity modulation of the cached far pair; no volume redraw.
            val farBrightness = .15 + 1.10 * (.5 + .5 * sin((cycle.elapsedSeconds + 12.0) * .22))
            val dt = if (previousPulseTime == 0L) 0.0 else ((now-previousPulseTime)/1e9).coerceIn(0.0,.25)
            previousPulseTime = now
            val targetPulse = (snapshot[4] * .85f + snapshot[2] * .15f).coerceIn(0f,1f)
            val response = 1.0 - exp(-dt / if (targetPulse > starPulse) .035 else .22)
            starPulse += (targetPulse-starPulse) * response.toFloat()
            GL.glUniform1f(starPulseUniform,starPulse)
            GL.glUniform1f(farBrightnessUniform, farBrightness.toFloat())
            for ((i,name) in arrayOf("uNear","uFar","uStars").withIndex()) {
                GL.glActiveTexture(GL.GL_TEXTURE0+i); GL.glBindTexture(GL.GL_TEXTURE_2D,textures[i])
                GL.glUniform1i(GL.glGetUniformLocation(compositeProgram,name),i)
            }
            GL.glUniform2f(GL.glGetUniformLocation(compositeProgram,"uResolution"),screenWidth.toFloat(),screenHeight.toFloat())
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
        } catch (e: Exception) { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0); fail(e) }
    }
}

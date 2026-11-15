package io.github.jqssun.airplay.ui

import android.opengl.GLES30 as GL
import android.util.Log
import kotlin.math.max

/** One-dimensional float geometry table. Full fibre shading and volume integration stay per pixel. */
internal class AuroraMaterialCache private constructor() {
    var volumeProgram = 0; private set
    private var materialProgram = 0
    private var noise: AuroraNoiseCache? = null
    private var texture = 0
    private var framebuffer = 0
    private var width = 0
    var uniforms = IntArray(0); private set
    var shapeUniforms = IntArray(0); private set
    var lifeUniforms = IntArray(0); private set
    var windUniform = -1; private set
    var sampleUniform = -1; private set
    private var geometryExtentUniform = -1
    private var materialUniforms = IntArray(0)

    private fun locations(program: Int, vararg names: String) =
        names.map { GL.glGetUniformLocation(program,it) }.toIntArray()

    private fun initialize(volume: String, shader: String, noiseShader: String, link: (String) -> Int) {
        check(GL.glGetString(GL.GL_EXTENSIONS).orEmpty().split(' ').contains("GL_EXT_color_buffer_float"))
        val maximum = IntArray(1); GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE,maximum,0)
        val viewport = IntArray(2); GL.glGetIntegerv(GL.GL_MAX_VIEWPORT_DIMS,viewport,0)
        val supportedWidth = minOf(maximum[0],viewport[0])
        check(supportedWidth >= 8192)
        width = minOf(16384,supportedWidth)
        noise = AuroraNoiseCache.create(noiseShader,link)
        val reference = volume.replace("#version 300 es","#version 300 es\n#define MATERIAL_LUT")
        volumeProgram = if (noise != null) try {
            link(reference.replace("#version 300 es","#version 300 es\n#define NOISE_LUT"))
        } catch (e: Exception) {
            noise?.release(); noise=null
            Log.w("Aurora","Noise shader unavailable; keeping geometry cache",e)
            link(reference)
        } else link(reference)
        noise?.attach(volumeProgram)
        materialProgram = link(shader)
        uniforms = locations(volumeProgram,"uResolution","uTime","uBass","uMid","uHigh","uEnergy","uBeat","uIntensity","uMotion")
        shapeUniforms = locations(volumeProgram,"uShapeA","uShapeB")
        lifeUniforms = locations(volumeProgram,"uBandAge","uBandFade")
        windUniform = GL.glGetUniformLocation(volumeProgram,"uDepthWind[0]")
        sampleUniform = GL.glGetUniformLocation(volumeProgram,"uSampleCount")
        geometryExtentUniform = GL.glGetUniformLocation(volumeProgram,"uGeometryExtent")
        materialUniforms = locations(materialProgram,"uTime","uShapeA","uShapeB","uGeometryExtent","uGeometryWidth")
        GL.glUseProgram(volumeProgram)
        GL.glUniform1i(GL.glGetUniformLocation(volumeProgram,"uGeometry"),3)
        val ids = IntArray(1)
        GL.glGenTextures(1,ids,0); texture=ids[0]
        GL.glActiveTexture(GL.GL_TEXTURE3); GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA32F,width,1,0,GL.GL_RGBA,GL.GL_FLOAT,null)
        GL.glGenFramebuffers(1,ids,0); framebuffer=ids[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,framebuffer)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,texture,0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE)
        check(GL.glGetError()==GL.GL_NO_ERROR)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        Log.i("Aurora","Material cache enabled: ${width}x1 RGBA32F")
    }

    fun prepare(time: Float, shapes: FloatArray, aspect: Float) {
        // Bounds cover the maximum camera ray slope at z=8.8, including wind and sway.
        val extent = max(32f,aspect*12f+2f)
        GL.glActiveTexture(GL.GL_TEXTURE3); GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,framebuffer)
        GL.glViewport(0,0,width,1); GL.glUseProgram(materialProgram)
        GL.glUniform1f(materialUniforms[0],time)
        GL.glUniform4fv(materialUniforms[1],1,shapes,0)
        GL.glUniform4fv(materialUniforms[2],1,shapes,4)
        GL.glUniform1f(materialUniforms[3],extent)
        GL.glUniform1f(materialUniforms[4],width.toFloat())
        GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
        noise?.prepare(time,volumeProgram)
        GL.glUseProgram(volumeProgram)
        GL.glUniform1f(geometryExtentUniform,extent)
    }

    fun release() {
        noise?.release(); noise=null
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        if (texture!=0) GL.glDeleteTextures(1,intArrayOf(texture),0)
        if (framebuffer!=0) GL.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        if (volumeProgram!=0) GL.glDeleteProgram(volumeProgram)
        if (materialProgram!=0) GL.glDeleteProgram(materialProgram)
        texture=0; framebuffer=0; volumeProgram=0; materialProgram=0
    }

    companion object {
        fun create(volume: String, shader: String, noiseShader: String, link: (String) -> Int): AuroraMaterialCache? {
            val result = AuroraMaterialCache()
            return try { result.initialize(volume,shader,noiseShader,link); result }
            catch (e: Exception) {
                result.release()
                // Allocation/format failures must not disable the original theme renderer.
                for (i in 0 until 8) if (GL.glGetError()==GL.GL_NO_ERROR) break
                Log.w("Aurora","Material cache unavailable; using reference renderer",e)
                null
            }
        }
    }
}

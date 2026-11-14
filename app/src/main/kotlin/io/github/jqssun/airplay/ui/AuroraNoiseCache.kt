package io.github.jqssun.airplay.ui

import android.opengl.GLES30 as GL
import android.util.Log
import kotlin.math.floor

/** Cache integer hash corners, not sampled noise. The shader keeps its original cubic interpolation. */
internal class AuroraNoiseCache private constructor() {
    private var program = 0
    private var texture = 0
    private var framebuffer = 0
    private var originsUniform = -1
    private var volumeOriginsUniform = -1
    private var prepared = false
    private val origins = IntArray(28)
    private val previous = IntArray(28)

    private fun initialize(shader: String, link: (String) -> Int) {
        program = link(shader)
        originsUniform = GL.glGetUniformLocation(program,"uNoiseOrigins[0]")
        val ids = IntArray(1)
        GL.glGenTextures(1,ids,0); texture=ids[0]
        GL.glActiveTexture(GL.GL_TEXTURE4); GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA32F,WIDTH,11,0,GL.GL_RGBA,GL.GL_FLOAT,null)
        GL.glGenFramebuffers(1,ids,0); framebuffer=ids[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,framebuffer)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,texture,0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE)
        check(GL.glGetError()==GL.GL_NO_ERROR)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        Log.i("Aurora","Integer noise cache enabled: ${WIDTH}x11 RGBA32F")
    }

    fun attach(volumeProgram: Int) {
        volumeOriginsUniform = GL.glGetUniformLocation(volumeProgram,"uNoiseOrigins[0]")
        val sampler = GL.glGetUniformLocation(volumeProgram,"uNoiseLattice")
        check(volumeOriginsUniform >= 0 && sampler >= 0)
        GL.glUseProgram(volumeProgram)
        GL.glUniform1i(sampler,4)
    }

    fun prepare(time: Float, volumeProgram: Int) {
        writeOrigins(time,origins)
        GL.glActiveTexture(GL.GL_TEXTURE4); GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
        if (!prepared || !origins.contentEquals(previous)) {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,framebuffer)
            // Last four rows are time-independent lifecycle hashes; generate them only once.
            GL.glViewport(0,0,WIDTH,if (prepared) 7 else 11)
            GL.glUseProgram(program)
            GL.glUniform4iv(originsUniform,7,origins,0)
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
            check(GL.glGetError()==GL.GL_NO_ERROR) { "Cannot refresh integer noise cache" }
            GL.glUseProgram(volumeProgram)
            GL.glUniform4iv(volumeOriginsUniform,7,origins,0)
            origins.copyInto(previous)
            prepared = true
        }
    }

    fun release() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        if (texture!=0) GL.glDeleteTextures(1,intArrayOf(texture),0)
        if (framebuffer!=0) GL.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        if (program!=0) GL.glDeleteProgram(program)
        texture=0; framebuffer=0; program=0; prepared=false
    }

    companion object {
        private const val WIDTH = 1024
        // x/y origin, first texture row, row count. All operations match shader float inputs.
        fun writeOrigins(time: Float, result: IntArray) {
            fun row(index: Int, x: Int, y: Int, offset: Int, count: Int) {
                result[index*4]=x; result[index*4+1]=y
                result[index*4+2]=offset; result[index*4+3]=count
            }
            row(0,floor(-time*.180f).toInt()-WIDTH/2,floor(.12f+2.64f*.07f+time*.017f).toInt(),0,3)
            row(1,floor(-time*.320f).toInt()-WIDTH/2,floor(2.64f*.15f+time*.022f).toInt(),3,1)
            row(2,-WIDTH/2,floor(.20f+time*.015f).toInt(),4,3)
            row(3,-WIDTH/2,floor(1.3f*3.7f).toInt(),7,1)
            row(4,-WIDTH/2,floor(1.3f*6.1f).toInt(),8,1)
            row(5,-WIDTH/2,floor(2.1f*3.7f).toInt(),9,1)
            row(6,-WIDTH/2,floor(2.1f*6.1f).toInt(),10,1)
        }
        fun create(shader: String, link: (String) -> Int): AuroraNoiseCache? {
            val result = AuroraNoiseCache()
            return try { result.initialize(shader,link); result }
            catch (e: Exception) {
                result.release()
                for (i in 0 until 8) if (GL.glGetError()==GL.GL_NO_ERROR) break
                Log.w("Aurora","Integer noise cache unavailable; keeping original noise",e)
                null
            }
        }
    }
}

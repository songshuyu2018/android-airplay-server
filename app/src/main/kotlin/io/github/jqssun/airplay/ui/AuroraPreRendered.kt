package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.opengl.GLES30 as GL
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.zip.Inflater
import kotlin.math.floor

/** Native compressed frames: no decoder or full-movie RAM cache. Frame I/O stays off the GL thread. */
internal class AuroraPreRendered private constructor(private val program: Int) {
    private class Source(context: Context, band: Int) : Closeable {
        val fd: AssetFileDescriptor = context.assets.openFd("aurora_band_$band.aurora")
        val stream = FileInputStream(fd.fileDescriptor)
        val channel: FileChannel = stream.channel
        val width: Int; val height: Int; val count: Int; val bytes: Int; val period: Float
        private val offsets: LongArray
        private val parts=ArrayList<AssetFileDescriptor>(2)
        private val partStreams=ArrayList<FileInputStream>(2)
        private val inflater=Inflater()
        private val trailer=ByteArray(1)
        private val compressed: ByteArray
        private val raw: ByteArray
        init {
            try {
                val h=ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
                read(h,fd.startOffset);h.flip()
                check(h.int==0x41555232 && h.int==3)
                width=h.int;height=h.int;count=h.int
                check(h.int==4 && h.int==4)
                bytes=h.int;period=h.float;val samples=h.int
                check(width in 1..2048 && height in 1..2048 && count in 4..2400)
                check(width==height*3)
                check(bytes==((width+3)/4)*((height+3)/4)*16 && bytes<=1048576)
                check(period.isFinite() && period in 1f..600f && samples==256)
                val index=ByteBuffer.allocate((count+1)*8).order(ByteOrder.BIG_ENDIAN)
                read(index,fd.startOffset+40);index.flip();offsets=LongArray(count+1) {index.long}
                check(offsets[0]==40L+(count+1)*8 && fd.length==40L+(count+1)*8 && offsets.last()<=400L*1024*1024)
                check((0 until count).all {offsets[it+1]>offsets[it] && offsets[it+1]-offsets[it]<=bytes+4096})
                val split=(count+1)/2
                for(part in 0..1) {
                    val data=context.assets.openFd("aurora_band_${band}_${part}.aurora")
                    parts.add(data);partStreams.add(FileInputStream(data.fileDescriptor))
                    val low=if(part==0)0 else split;val high=if(part==0)split else count
                    check(data.length==offsets[high]-offsets[low])
                }
                compressed=ByteArray(bytes+4096);raw=ByteArray(bytes)
            } catch(e: Throwable) {close();throw e}
        }
        fun read(buffer: ByteBuffer, offset: Long, from: FileChannel=channel) {
            var pos=offset
            while(buffer.hasRemaining()) {val n=from.read(buffer,pos);check(n>0) {"Incomplete aurora cache"};pos+=n}
        }
        fun frame(index: Int, buffer: ByteBuffer) {
            val length=(offsets[index+1]-offsets[index]).toInt()
            val split=(count+1)/2;val part=if(index<split)0 else 1;val base=if(part==0)0 else split
            read(ByteBuffer.wrap(compressed,0,length),parts[part].startOffset+offsets[index]-offsets[base],partStreams[part].channel)
            inflater.reset();inflater.setInput(compressed,0,length)
            var written=0
            while(written<bytes) {val n=inflater.inflate(raw,written,bytes-written);check(n>0) {"Invalid compressed aurora frame"};written+=n}
            if(!inflater.finished())check(inflater.inflate(trailer)==0)
            check(inflater.finished() && inflater.remaining==0)
            buffer.clear();buffer.put(raw);buffer.flip()
        }
        override fun close() {runCatching {channel.close()};runCatching {fd.close()};partStreams.forEach {runCatching {it.close()}};parts.forEach {runCatching {it.close()}};runCatching {inflater.end()}}
    }
    private val sources=ArrayList<Source>(2)
    private val lock=Any()
    private val pool=ArrayList<ByteBuffer>(12)
    private val loaded=LinkedHashMap<Int,ByteBuffer>()
    private val pending=HashSet<Int>()
    private var needed=emptySet<Int>()
    @Volatile private var closed=false
    @Volatile private var error: Exception?=null
    private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"aurora-cache-reader").apply {isDaemon=true} }
    private val textures=IntArray(6)
    private val uploaded=IntArray(6) {-1}
    private val displayed=IntArray(4)
    private val fractions=FloatArray(2)
    private var hasFrame=false
    private val locations=IntArray(5)
    private val sceneTexture=IntArray(1)
    private val sceneFramebuffer=IntArray(1)
    private var sceneWidth=0
    private var sceneHeight=0
    private var outputUniform=-1
    private var resolutionUniform=-1
    private fun initialize(context: Context) {
        val extensions=GL.glGetString(GL.GL_EXTENSIONS).orEmpty().split(' ')
        check("GL_KHR_texture_compression_astc_ldr" in extensions || GL.glGetString(GL.GL_VERSION).orEmpty().startsWith("OpenGL ES 3.2"))
        sources.add(Source(context,0));sources.add(Source(context,1))
        check(sources[0].width==sources[1].width && sources[0].height==sources[1].height && sources[0].count==sources[1].count && sources[0].period==sources[1].period)
        repeat(12) {pool.add(ByteBuffer.allocateDirect(sources[0].bytes))}
        GL.glGenTextures(6,textures,0)
        GL.glGenTextures(1,sceneTexture,0)
        GL.glGenFramebuffers(1,sceneFramebuffer,0)
        outputUniform=GL.glGetUniformLocation(program,"uCachedOutputOnly")
        resolutionUniform=GL.glGetUniformLocation(program,"uResolution")
        locations[0]=GL.glGetUniformLocation(program,"uCachedBlend")
        locations[1]=GL.glGetUniformLocation(program,"uCachedFractions")
        locations[2]=GL.glGetUniformLocation(program,"uCachedLife")
        locations[3]=GL.glGetUniformLocation(program,"uCachedMusic")
        locations[4]=GL.glGetUniformLocation(program,"uCachedAspect")
        GL.glUseProgram(program)
        for(i in 0..3)GL.glUniform1i(GL.glGetUniformLocation(program,"uCached$i"),5+i)
        check(GL.glGetError()==GL.GL_NO_ERROR)
    }
    private fun slot(key: Int): Int {
        val count=sources[0].count;val index=key%count
        val colour=if(index==count-1 && count%3==1)1 else index%3
        return (key/count)*3+colour
    }
    private fun have(key: Int)=uploaded[slot(key)]==key || loaded.containsKey(key)
    private fun request(key: Int) {
        synchronized(lock) {
            if(closed || have(key) || key in pending || pending.size>=12)return
            pending.add(key)
        }
        worker.execute {
            var buffer: ByteBuffer?=null
            try {
                synchronized(lock) {
                    if(!closed) {
                        if(pool.isEmpty()) {
                            val evict=loaded.entries.firstOrNull {it.key !in needed}
                            if(evict!=null) {pool.add(evict.value);loaded.remove(evict.key)}
                        }
                        if(pool.isNotEmpty())buffer=pool.removeAt(pool.lastIndex)
                    }
                }
                val data=buffer
                if(data!=null && !closed) {
                    val count=sources[0].count;sources[key/count].frame(key%count,data)
                    synchronized(lock) {if(!closed) {loaded.put(key,data)?.let {pool.add(it)};buffer=null}}
                }
            } catch(e: Exception) {if(!closed)error=e}
            finally {synchronized(lock) {buffer?.let {if(!closed)pool.add(it)};pending.remove(key)}}
        }
    }
    /** Atomically install both temporal pairs; a late read holds the last valid image. */
    fun prepare(time: Double, generationA: Int, generationB: Int, aspect: Float): Boolean {
        error?.let {throw it}
        if(closed || aspect>3f)return false
        val count=sources[0].count;val period=sources[0].period
        val keys=IntArray(4);val blends=FloatArray(2)
        for(band in 0..1) {
            val generation=if(band==0)generationA else generationB
            val offset=generation*.618033988749895+band*.371
            val phase=time*.5/period+offset
            val position=(phase-floor(phase))*count
            val first=floor(position).toInt().coerceIn(0,count-1)
            keys[band*2]=band*count+first;keys[band*2+1]=band*count+(first+1)%count
            blends[band]=(position-first).toFloat()
        }
        synchronized(lock) {needed=keys.toSet()}
        keys.forEach(::request)
        for(band in 0..1)for(ahead in 2..3)request(band*count+(keys[band*2]%count+ahead)%count)
        synchronized(lock) {
            if(keys.all {have(it)}) {
                for(key in keys) {
                    val slot=slot(key)
                    if(uploaded[slot]!=key) {
                        val data=loaded.remove(key)!!
                        try {
                            GL.glActiveTexture(GL.GL_TEXTURE9);GL.glBindTexture(GL.GL_TEXTURE_2D,textures[slot])
                            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
                            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                            data.position(0)
                            if(uploaded[slot]<0)
                                GL.glCompressedTexImage2D(GL.GL_TEXTURE_2D,0,0x93B0,sources[0].width,sources[0].height,0,sources[0].bytes,data)
                            else GL.glCompressedTexSubImage2D(GL.GL_TEXTURE_2D,0,0,0,sources[0].width,sources[0].height,0x93B0,sources[0].bytes,data)
                            check(GL.glGetError()==GL.GL_NO_ERROR) {"ASTC frame upload failed"}
                            uploaded[slot]=key
                        } finally {pool.add(data)}
                    }
                }
                keys.copyInto(displayed);blends.copyInto(fractions);hasFrame=true
            }
        }
        return hasFrame
    }
    fun bind(blend: Float, aspect: Float, ageA: Float, ageB: Float, fadeA: Float, fadeB: Float, music: Float) {
        GL.glUniform1f(locations[0],if(hasFrame)blend else 0f)
        if(!hasFrame)return
        GL.glUniform2fv(locations[1],1,fractions,0)
        GL.glUniform4f(locations[2],ageA,ageB,fadeA,fadeB)
        GL.glUniform1f(locations[3],music)
        GL.glUniform1f(locations[4],aspect)
        for(i in 0..3) {GL.glActiveTexture(GL.GL_TEXTURE0+5+i);GL.glBindTexture(GL.GL_TEXTURE_2D,textures[slot(displayed[i])])}
    }
    /** Evaluate reveal/exposure at material resolution; native stars stay in the final pass. */
    fun renderNear(blend: Float, aspect: Float, ageA: Float, ageB: Float, fadeA: Float, fadeB: Float,
        music: Float, liveTexture: Int, outputWidth: Int, outputHeight: Int): Int {
        val scale=minOf(1f,sources[0].height.toFloat()/outputHeight)
        val width=maxOf(1,kotlin.math.round(outputWidth*scale).toInt())
        val height=maxOf(1,kotlin.math.round(outputHeight*scale).toInt())
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,sceneFramebuffer[0])
        if(sceneWidth!=width || sceneHeight!=height) {
            GL.glActiveTexture(GL.GL_TEXTURE9);GL.glBindTexture(GL.GL_TEXTURE_2D,sceneTexture[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA8,width,height,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,sceneTexture[0],0)
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE && GL.glGetError()==GL.GL_NO_ERROR)
            sceneWidth=width;sceneHeight=height
        }
        GL.glViewport(0,0,width,height);GL.glUseProgram(program)
        GL.glUniform1i(outputUniform,1);GL.glUniform2f(resolutionUniform,width.toFloat(),height.toFloat())
        try {
            GL.glActiveTexture(GL.GL_TEXTURE0);GL.glBindTexture(GL.GL_TEXTURE_2D,liveTexture)
            bind(blend,aspect,ageA,ageB,fadeA,fadeB,music)
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
        } finally {GL.glUniform1i(outputUniform,0);GL.glUniform1f(locations[0],0f)}
        return sceneTexture[0]
    }
    fun release(deleteTextures: Boolean=true) {
        closed=true;worker.shutdownNow();sources.forEach {runCatching {it.close()}}
        synchronized(lock) {loaded.clear();pool.clear();pending.clear()}
        if(deleteTextures && textures.any {it!=0})GL.glDeleteTextures(6,textures,0)
        if(deleteTextures && sceneFramebuffer[0]!=0)GL.glDeleteFramebuffers(1,sceneFramebuffer,0)
        if(deleteTextures && sceneTexture[0]!=0)GL.glDeleteTextures(1,sceneTexture,0)
        sceneFramebuffer[0]=0;sceneTexture[0]=0
        textures.fill(0);hasFrame=false
    }
    companion object {
        fun create(context: Context, program: Int): AuroraPreRendered? {
            val result=AuroraPreRendered(program)
            return try {result.initialize(context);result} catch(e: Exception) {
                result.release();repeat(8) {if(GL.glGetError()==GL.GL_NO_ERROR)return@repeat}
                Log.w("Aurora","Pre-render cache unavailable; using live aurora",e);null
            } catch(e: OutOfMemoryError) {
                result.release()
                Log.w("Aurora","Insufficient cache memory; using live aurora",e);null
            }
        }
    }
}

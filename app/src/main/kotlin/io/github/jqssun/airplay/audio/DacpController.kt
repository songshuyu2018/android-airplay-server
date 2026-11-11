package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** A single main-looper owner coordinates NSD and a session-scoped command queue. */
class DacpController(ctx: Context, private val onStateChanged: () -> Unit = {}) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val state = DacpControlState()
    private val results = mutableMapOf<Long, SettableFuture<Unit>>()
    @Volatile var dacpId = ""; private set
    @Volatile var activeRemote = ""; private set
    val ready: Boolean get() = state.ready()
    // Capability survives temporary endpoint loss: a press can initiate recovery.
    val canControl: Boolean get() = state.canControl()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolveToken = 0L
    private var nextResolveToken = 0L
    private var pendingResolve: NsdServiceInfo? = null
    private var retryStep = 0
    private var recoveryNeeded = false
    private var recoveringScheduled = false
    private var recoveryAt = 0L
    private var lastBrowseAt = -10000L
    private var closed = false
    private var lastReady = false
    private var lastCapable = false
    private val recovery = Runnable {
        recoveringScheduled = false
        if (canControl && (recoveryNeeded || !ready)) {
            stopDiscovery()
            startDiscovery()
            scheduleRecovery()
        }
    }
    private val expiry = object : Runnable {
        override fun run() {
            if (closed) return
            state.expire(SystemClock.elapsedRealtime())
            drain()
            if (results.isNotEmpty()) main.postDelayed(this, 500)
        }
    }
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
    fun update(dacpId: String, activeRemote: String) = onMain {
        if (closed) return@onMain
        if (state.update(dacpId, activeRemote)) {
            this.dacpId = dacpId; this.activeRemote = activeRemote
            stopDiscovery()
            pendingResolve = null
            retryStep = 0
            main.removeCallbacks(recovery); recoveringScheduled = false
            recoveryNeeded = true
        }
        if (canControl && discovery == null) startDiscovery()
        if (canControl && !ready) scheduleRecovery()
        drain(); publish(); pump()
    }
    fun reset() = onMain {
        state.reset(); dacpId = ""; activeRemote = ""
        stopDiscovery(); pendingResolve = null
        main.removeCallbacks(recovery); recoveringScheduled = false
        recoveryNeeded = false; retryStep = 0
        drain(); publish()
    }
    fun release() = onMain {
        if (closed) return@onMain
        closed = true; reset(); state.close(); drain()
        main.removeCallbacks(expiry)
        worker.shutdownNow()
    }
    fun play(source: String = "app") = send("/ctrl-int/1/play", source)
    fun pause(source: String = "app") = send("/ctrl-int/1/pause", source)
    fun nextItem() = send("/ctrl-int/1/nextitem", "app")
    fun prevItem() = send("/ctrl-int/1/previtem", "app")
    fun volumeUp() = send("/ctrl-int/1/volumeup", "app")
    fun volumeDown() = send("/ctrl-int/1/volumedown", "app")
    fun beginFastForward() = send("/ctrl-int/1/beginff", "app")
    fun beginRewind() = send("/ctrl-int/1/beginrew", "app")
    fun playResume() = send("/ctrl-int/1/playresume", "app")

    private fun publish() {
        if (lastReady == ready && lastCapable == canControl) return
        lastReady = ready; lastCapable = canControl
        Log.i(TAG, "DACP state session=${state.session()} capable=$canControl ready=$ready")
        if (!closed) onStateChanged()
    }
    private fun drain() {
        for (completion in state.completions()) {
            Log.i(TAG, "DACP command=${completion.id} result=${completion.outcome}")
            val result = results.remove(completion.id) ?: continue
            if (completion.outcome == DacpControlState.Outcome.SUCCESS) result.set(Unit)
            else result.setException(IOException("DACP ${completion.outcome}"))
        }
    }
    private fun send(path: String, source: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        onMain {
            if (closed) { result.setException(IOException("DACP released")); return@onMain }
            val id = state.enqueue(path, SystemClock.elapsedRealtime())
            results[id] = result
            main.removeCallbacks(expiry)
            main.postDelayed(expiry, 500)
            Log.i(TAG, "DACP command=$id session=${state.session()} source=$source path=$path ready=$ready")
            drain()
            if (canControl && !ready) { recoveryNeeded = true; scheduleRecovery(immediate = true) }
            pump()
        }
        return result
    }
    private fun pump() {
        val attempt = state.claim(SystemClock.elapsedRealtime())
        drain()
        if (attempt == null) return
        try {
            worker.execute {
                val attempted = state.shouldSend(attempt, SystemClock.elapsedRealtime())
                val success = attempted && fetch(attempt)
                main.post {
                    state.complete(attempt, success, attempted, SystemClock.elapsedRealtime())
                    drain(); publish()
                    if (!closed && canControl && !ready) {
                        recoveryNeeded = true
                        scheduleRecovery(immediate = true)
                    }
                    if (!closed) pump()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            state.complete(attempt, false, false, SystemClock.elapsedRealtime())
            drain()
        }
    }

    private fun scheduleRecovery(immediate: Boolean = false) {
        if (closed || !canControl) return
        val now = SystemClock.elapsedRealtime()
        val delay = if (immediate) maxOf(0L, 1000L - (now - lastBrowseAt))
            else minOf(8000L, 500L shl minOf(retryStep, 4))
        if (recoveringScheduled && recoveryAt <= now + delay) return
        main.removeCallbacks(recovery)
        recoveringScheduled = true; recoveryAt = now + delay
        retryStep = minOf(retryStep + 1, 4)
        main.postDelayed(recovery, delay)
    }
    private fun stopDiscovery() {
        val old = discovery ?: return
        discovery = null
        try { nsd.stopServiceDiscovery(old) } catch (_: Exception) { }
    }
    private fun startDiscovery() {
        if (closed || !canControl || discovery != null) return
        val owner = state.session()
        val wanted = "iTunes_Ctrl_$dacpId"
        val listener = object : NsdManager.DiscoveryListener {
            private fun valid() = !closed && state.session() == owner && discovery === this
            override fun onDiscoveryStarted(type: String) { }
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (valid() && info.serviceName.equals(wanted, ignoreCase = true)) resolve(info)
            } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                if (valid() && info.serviceName.equals(wanted, ignoreCase = true)) {
                    // mDNS disappearance is a discovery signal, not proof the last
                    // working HTTP endpoint is dead. Probe it while refreshing NSD.
                    Log.w(TAG, "DACP service lost session=$owner; refreshing endpoint")
                    recoveryNeeded = true
                    scheduleRecovery()
                }
            } }
            override fun onDiscoveryStopped(type: String) { main.post {
                if (valid()) { discovery = null; recoveryNeeded = true; scheduleRecovery() }
            } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post {
                if (valid()) {
                    discovery = null; recoveryNeeded = true
                    Log.w(TAG, "DACP browse failed session=$owner code=$code")
                    scheduleRecovery()
                }
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) { }
        }
        discovery = listener
        lastBrowseAt = SystemClock.elapsedRealtime()
        try { nsd.discoverServices("_dacp._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) {
            discovery = null; recoveryNeeded = true
            Log.w(TAG, "DACP browse error", e); scheduleRecovery()
        }
    }
    private fun resolve(info: NsdServiceInfo) {
        if (closed || !canControl) return
        if (resolveToken != 0L) { pendingResolve = info; return }
        val owner = state.session()
        val token = ++nextResolveToken
        resolveToken = token
        fun finish(resolved: NsdServiceInfo?, error: String?) {
            if (token != resolveToken) return
            resolveToken = 0
            if (!closed && owner == state.session()) {
                val address = resolved?.host?.hostAddress
                if (resolved != null && !address.isNullOrEmpty() && state.endpoint(owner, address, resolved.port)) {
                    recoveryNeeded = false; retryStep = 0
                    main.removeCallbacks(recovery); recoveringScheduled = false
                    Log.i(TAG, "DACP resolved session=$owner $address:${resolved.port}")
                    publish(); pump()
                } else {
                    recoveryNeeded = true
                    Log.w(TAG, "DACP resolve failed session=$owner reason=$error")
                    scheduleRecovery()
                }
            }
            val next = pendingResolve
            pendingResolve = null
            if (next != null && !closed && canControl) resolve(next)
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(result: NsdServiceInfo) { main.post { finish(result, null) } }
                override fun onResolveFailed(result: NsdServiceInfo, code: Int) { main.post { finish(null, code.toString()) } }
            })
            main.postDelayed({ finish(null, "timeout") }, 4000)
        } catch (e: Exception) { finish(null, e.javaClass.simpleName) }
    }
    private fun fetch(a: DacpControlState.Attempt): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            // The component constructor also brackets IPv6 literals correctly.
            val url = URL("http", a.host, a.port, a.path)
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Active-Remote", a.activeRemote)
            conn.setRequestProperty("DACP-ID", a.dacpId)
            conn.setRequestProperty("Host", url.authority)
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            val code = conn.responseCode
            Log.i(TAG, "DACP command=${a.id} session=${a.session} attempt=${a.number} path=${a.path} HTTP=$code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "DACP command=${a.id} session=${a.session} attempt=${a.number} failed: ${e.javaClass.simpleName}")
            false
        } finally { connection?.disconnect() }
    }
    companion object { private const val TAG = "DacpController" }
}

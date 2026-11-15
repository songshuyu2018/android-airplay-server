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
    private val cache = DacpEndpointCache(ctx)
    private var senderAddress = ""
    private val rejectedHints = mutableMapOf<Pair<String, Int>, Long>()
    private val results = mutableMapOf<Long, SettableFuture<Unit>>()
    @Volatile var dacpId = ""; private set
    @Volatile var activeRemote = ""; private set
    val ready: Boolean get() = state.ready()
    // Capability survives temporary endpoint loss: a press can initiate recovery.
    val canControl: Boolean get() = state.canControl()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var nextResolveToken = 0L
    private data class Resolution(val token: Long, val owner: Long, val info: NsdServiceInfo)
    private var resolving: Resolution? = null
    private var pendingResolve: Resolution? = null
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
            installHint(); publish(); pump()
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
    @JvmOverloads
    fun update(dacpId: String, activeRemote: String, senderAddress: String = "") = onMain {
        if (closed) return@onMain
        // A peer change is also an ownership change, even if the sender reused its IDs.
        if (this.senderAddress != senderAddress) state.reset()
        if (state.update(dacpId, activeRemote) || this.senderAddress != senderAddress) {
            this.senderAddress = senderAddress
            rejectedHints.clear()
            this.dacpId = dacpId; this.activeRemote = activeRemote
            stopDiscovery()
            pendingResolve = null
            retryStep = 0
            main.removeCallbacks(recovery); recoveringScheduled = false
            recoveryNeeded = true
        }
        installHint()
        if (canControl && discovery == null) startDiscovery()
        if (canControl && (recoveryNeeded || !ready)) scheduleRecovery()
        drain(); publish(); pump()
    }
    fun reset() = onMain {
        state.reset(); dacpId = ""; activeRemote = ""; senderAddress = ""
        rejectedHints.clear()
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
            installHint()
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
                    if (!closed && attempted && state.ownsEndpoint(attempt)) {
                        if (success) {
                            cache.remember(attempt.dacpId, attempt.host, attempt.port)
                            rejectedHints.remove(attempt.host to attempt.port)
                            // A working unicast endpoint does not need a periodic
                            // browse restart just because multicast is unavailable.
                            recoveryNeeded = false; retryStep = 0
                            main.removeCallbacks(recovery); recoveringScheduled = false
                        } else {
                            cache.forget(attempt.dacpId, attempt.host, attempt.port)
                            rejectedHints[attempt.host to attempt.port] = SystemClock.elapsedRealtime() + 2000
                        }
                    }
                    state.complete(attempt, success, attempted, SystemClock.elapsedRealtime())
                    installHint()
                    drain(); publish()
                    if (!closed && canControl && (!ready || (attempted && !success))) {
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
    /** Hints never carry credentials and never execute a transport command by themselves. */
    private fun installHint() {
        if (closed || !canControl || ready || senderAddress.isBlank()) return
        val cachedPort = cache.port(dacpId, senderAddress)
        val ports = listOfNotNull(cachedPort, 39831).distinct()
        val now = SystemClock.elapsedRealtime()
        val port = ports.firstOrNull { (rejectedHints[senderAddress to it] ?: 0L) <= now } ?: return
        if (state.endpoint(state.session(), senderAddress, port)) {
            val source = if (port == cachedPort) "cache" else "ma-default"
            Log.i(TAG, "DACP endpoint hint session=${state.session()} source=$source $senderAddress:$port")
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (closed || !canControl) return
        val request = Resolution(++nextResolveToken, state.session(), info)
        val active = resolving
        if (active != null) {
            // Legacy Android NSD permits one resolve per client. A local watchdog
            // cannot cancel it: retain ownership until the real terminal callback.
            if (active.owner != request.owner || active.info.serviceName != info.serviceName)
                pendingResolve = request
            return
        }
        resolving = request
        Log.i(TAG, "DACP resolve start session=${request.owner} task=${request.token} service=${info.serviceName}")
        val watchdog = Runnable {
            if (resolving === request && !closed && request.owner == state.session()) {
                Log.w(TAG, "DACP resolve slow session=${request.owner}; keeping system task ${request.token}")
                if (!ready) { recoveryNeeded = true; scheduleRecovery() }
            }
        }
        fun finish(resolved: NsdServiceInfo?, error: String?) {
            if (resolving !== request) return
            resolving = null
            main.removeCallbacks(watchdog)
            if (!closed && request.owner == state.session()) {
                // The RTSP peer is authoritative for routing; NSD supplies the
                // port and must not redirect us to an address cached before restart.
                val address = senderAddress.ifBlank { resolved?.host?.hostAddress.orEmpty() }
                val nameMatches = resolved?.serviceName.equals("iTunes_Ctrl_$dacpId", ignoreCase = true)
                if (resolved != null && nameMatches && !address.isNullOrEmpty() &&
                    state.endpoint(request.owner, address, resolved.port)) {
                    cache.remember(dacpId, address, resolved.port)
                    recoveryNeeded = false; retryStep = 0
                    main.removeCallbacks(recovery); recoveringScheduled = false
                    Log.i(TAG, "DACP resolved session=${request.owner} $address:${resolved.port}")
                    publish(); pump()
                } else {
                    Log.w(TAG, "DACP resolve failed session=${request.owner} reason=$error")
                    recoveryNeeded = recoveryNeeded || !ready
                    if (recoveryNeeded) scheduleRecovery()
                }
            } else if (!closed) {
                Log.i(TAG, "DACP resolve ignored old session=${request.owner} current=${state.session()}")
            }
            val next = pendingResolve
            pendingResolve = null
            if (next != null && !closed && next.owner == state.session() && canControl) resolve(next.info)
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(result: NsdServiceInfo) { main.post { finish(result, null) } }
                override fun onResolveFailed(result: NsdServiceInfo, code: Int) { main.post { finish(null, code.toString()) } }
            })
            main.postDelayed(watchdog, 4000)
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

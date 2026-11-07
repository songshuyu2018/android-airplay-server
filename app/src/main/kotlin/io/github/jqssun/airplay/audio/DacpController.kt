package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 *
 * The sender registers an mDNS service `iTunes_Ctrl_<DACP-ID>._dacp._tcp`; the app
 * learned the identifier from the session (SETUP's DACP-ID / Active-Remote headers,
 * forwarded by the receiver as `ssnc/daid` and `ssnc/acre`).
 *
 * Resolution deliberately browses rather than resolving a synthesised NsdServiceInfo:
 * resolve-by-name only answers from the system daemon's cache, which keeps serving the
 * sender's *previous* address and port after the sender restarts.  A browse makes the
 * responder announce the current SRV/A records again, and the answers arrive through
 * onServiceResolved() from that announcement.
 *
 * A command issued while the endpoint is still unknown is remembered briefly and sent
 * as soon as it resolves: the first press after a sender connects must not be lost.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()
    private val _handler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile var dacpId = ""
        private set
    @Volatile var activeRemote = ""
        private set
    @Volatile private var host = ""
    @Volatile private var port = 0

    private val lock = Any()
    @Volatile private var generation = 0L
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var pendingResolve: NsdServiceInfo? = null
    private var resolveRetries = 0
    private var browseAttempts = 0
    private val pendingCommands = ArrayDeque<Pair<String, Long>>()

    val ready: Boolean get() = host.isNotEmpty() && port > 0 && activeRemote.isNotEmpty()

    fun update(dacpId: String, activeRemote: String) {
        val idChanged = this.dacpId != dacpId || this.activeRemote != activeRemote
        if (idChanged) synchronized(lock) {
            generation++
            pendingCommands.clear()
            pendingResolve = null
            resolveRetries = 0
            browseAttempts = 0
        }
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        if (idChanged) {
            // a different sender: whatever we resolved before cannot be it
            host = ""
            port = 0
        }
        if (!ready || idChanged) _discover(force = idChanged)
        _flushPending()
    }

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")
    fun beginFastForward() = _send("/ctrl-int/1/beginff")
    fun beginRewind() = _send("/ctrl-int/1/beginrew")
    fun playResume() = _send("/ctrl-int/1/playresume")

    fun reset() {
        synchronized(lock) {
            generation++
            pendingResolve = null
            resolveRetries = 0
            browseAttempts = 0
        }
        dacpId = ""
        activeRemote = ""
        host = ""
        port = 0
        synchronized(lock) {
            pendingCommands.clear()
            _stopDiscoveryLocked()
        }
    }

    fun release() {
        reset()
        exec.shutdownNow()
    }

    /* ---------------------------------------------------------------- discovery */

    private fun _discover(force: Boolean) {
        if (dacpId.isEmpty()) return
        synchronized(lock) {
            if (discovery != null) {
                if (!force) return
                _stopDiscoveryLocked()
            }
            if (browseAttempts > 4) return
            val session = generation
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "DACP browse started for $serviceType")
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (session != generation) return
                    val wanted = "iTunes_Ctrl_$dacpId"
                    if (!info.serviceName.equals(wanted, ignoreCase = true)) return
                    Log.i(TAG, "DACP service found: ${info.serviceName}")
                    _resolve(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    if (session == generation && info.serviceName.equals("iTunes_Ctrl_$dacpId", ignoreCase = true)) {
                        Log.i(TAG, "DACP service lost: ${info.serviceName}")
                        if (host.isNotEmpty() && info.serviceName.endsWith(dacpId)) {
                            host = ""
                            port = 0
                        }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "DACP browse failed to start: $errorCode")
                    synchronized(lock) { discovery = null }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "DACP browse failed to stop: $errorCode")
                }
            }
            discovery = listener
            browseAttempts++
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.w(TAG, "DACP browse error", e)
                discovery = null
            }
        }
    }

    private fun _stopDiscoveryLocked() {
        val listener = discovery ?: return
        discovery = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    private fun _resolve(info: NsdServiceInfo) {
        val session = generation
        if (!info.serviceName.equals("iTunes_Ctrl_$dacpId", ignoreCase = true)) return
        synchronized(lock) {
            if (resolving) {
                pendingResolve = info
                return
            }
            resolving = true
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    synchronized(lock) { resolving = false }
                    if (session != generation) { _nextResolve(); return }
                    if (code == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        // another resolve is in flight: try again shortly
                        _retryResolve(si, 500)
                    } else {
                        Log.w(TAG, "DACP resolve failed: $code")
                        _retryResolve(si, 1500)
                    }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    synchronized(lock) { resolving = false }
                    if (session != generation) { _nextResolve(); return }
                    val addr = si.host?.hostAddress
                    if (addr.isNullOrEmpty()) {
                        _retryResolve(si, 1500)
                        return
                    }
                    host = addr
                    port = si.port
                    resolveRetries = 0
                    browseAttempts = 0
                    Log.i(TAG, "DACP resolved: $addr:${si.port} (${si.serviceName})")
                    _nextResolve()
                    _flushPending()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
            synchronized(lock) { resolving = false }
            _nextResolve()
        }
    }

    private fun _nextResolve() {
        val next = synchronized(lock) {
            val n = pendingResolve
            pendingResolve = null
            n
        }
        if (next != null && !ready) _resolve(next)
    }

    private fun _retryResolve(info: NsdServiceInfo, delayMs: Long) {
        synchronized(lock) {
            if (resolveRetries > 3) return
            resolveRetries++
        }
        val session = generation
        val retry = Runnable { if (session == generation && !ready) _resolve(info) }
        _handler.postDelayed(retry, delayMs)
    }

    /** Run on the single worker thread, ignoring a send that races the shutdown. */
    private fun _post(block: () -> Unit) {
        try {
            exec.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /* ------------------------------------------------------------------- sending */

    private fun _send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        if (!ready) {
            Log.i(TAG, "DACP $path queued: endpoint not resolved yet")
            synchronized(lock) {
                pendingCommands.addLast(path to System.currentTimeMillis())
                while (pendingCommands.size > 8) pendingCommands.removeFirst()
            }
            _discover(force = false)
            result.setException(IOException("dacp endpoint not resolved"))
            return result
        }
        val h = host
        val p = port
        val session = generation
        val remote = activeRemote
        val id = dacpId
        _post {
            if (session != generation) {
                result.setException(IOException("DACP session changed"))
                return@_post
            }
            if (_fetch(h, p, path, id, remote)) {
                result.set(Unit)
            } else {
                if (session != generation) {
                    result.setException(IOException("DACP session changed"))
                    return@_post
                }
                // the sender may have moved: forget the endpoint and look again
                host = ""
                port = 0
                synchronized(lock) {
                    pendingCommands.addLast(path to System.currentTimeMillis())
                    while (pendingCommands.size > 8) pendingCommands.removeFirst()
                }
                _discover(force = true)
                result.setException(IOException("DACP send failed"))
            }
        }
        return result
    }

    private fun _flushPending() {
        if (!ready) return
        val commands = synchronized(lock) {
            val now = System.currentTimeMillis()
            val due = pendingCommands.filter { now - it.second <= PENDING_TTL_MS }.map { it.first }
            pendingCommands.clear()
            due
        }
        if (commands.isEmpty()) return
        val h = host
        val p = port
        val session = generation
        val remote = activeRemote
        val id = dacpId
        _post {
            for (path in commands) {
                if (session != generation || !_fetch(h, p, path, id, remote)) break
            }
        }
    }

    private fun _fetch(h: String, p: Int, path: String, id: String, remote: String): Boolean {
        val url = "http://$h:$p$path"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            // Apple's DACP identifies the session by BOTH headers; a request carrying
            // only Active-Remote is ignored by most senders.
            conn.setRequestProperty("Active-Remote", remote)
            if (id.isNotEmpty()) conn.setRequestProperty("DACP-ID", id)
            conn.setRequestProperty("Host", "$h:$p")
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            try {
                conn.inputStream.readBytes()
            } catch (_: Exception) {
            }
            conn.disconnect()
            if (code in 200..299) {
                Log.i(TAG, "DACP $path -> $code")
                true
            } else {
                Log.w(TAG, "DACP $path -> HTTP $code")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "DACP send failed: $path ($h:$p): ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "DacpController"
        private const val SERVICE_TYPE = "_dacp._tcp"
        private const val PENDING_TTL_MS = 5000L
    }
}

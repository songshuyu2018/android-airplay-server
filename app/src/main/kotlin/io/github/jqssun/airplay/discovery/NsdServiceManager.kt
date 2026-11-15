package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    @Suppress("DEPRECATION")
    fun acquireNetworkLocks() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            if (multicastLock?.isHeld != true) {
                multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot acquire multicast lock", e)
        }
        try {
            if (wifiLock?.isHeld != true) {
                // Android 12: keep the radio awake with the receiver, including
                // screen-off DACP discovery. Newer Android versions may restrict
                // this mode; endpoint caching remains the primary recovery path.
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay_receiver").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot acquire Wi-Fi lock", e)
        }
        Log.i(TAG, "Receiver network locks multicast=${multicastLock?.isHeld == true} wifi=${wifiLock?.isHeld == true}")
    }

    fun register(services: List<MdnsService>) = services.forEach(::_register)

    private fun _register(s: MdnsService) {
        val info = NsdServiceInfo().apply {
            serviceName = s.name
            serviceType = s.type
            port = s.port
            s.txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += listener
    }

    fun release() {
        registrations.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrations.clear()
        if (multicastLock?.isHeld == true) multicastLock?.release()
        multicastLock = null
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wifiLock = null
        Log.i(TAG, "Receiver network locks released")
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}

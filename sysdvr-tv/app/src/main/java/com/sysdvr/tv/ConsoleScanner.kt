package com.sysdvr.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Descubrimiento por UDP: la consola emite cada 2 s un descriptor
 * "SysDVR|ver|proto|serial" al puerto 19999 (broadcast).
 */
class ConsoleScanner(
    context: Context,
    private val onFound: (Protocol.DeviceInfo) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        // Algunos dispositivos filtran broadcast con la pantalla en reposo o sin este lock
        lock = wifi?.createMulticastLock("sysdvr-scan")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        thread = Thread(::loop, "sysdvr-scan").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        socket?.close()
        thread?.join(500)
        thread = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    private fun loop() {
        val seen = HashSet<String>()
        try {
            DatagramSocket(null).use { s ->
                socket = s
                s.reuseAddress = true
                s.broadcast = true
                s.soTimeout = 1000
                s.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                val buf = ByteArray(512)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = pkt.address?.hostAddress ?: continue
                    if (!seen.add(ip)) continue
                    val info = Protocol.parseBeacon(pkt.data, pkt.length, ip)
                    if (info == null) {
                        seen.remove(ip)
                        continue
                    }
                    Log.i(TAG, "Consola encontrada: $info")
                    onFound(info)
                }
            }
        } catch (e: SocketException) {
            if (running) onError("Búsqueda UDP falló: ${e.message}")
        } catch (e: Exception) {
            if (running) onError("Búsqueda UDP falló: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SysDVR"
    }
}

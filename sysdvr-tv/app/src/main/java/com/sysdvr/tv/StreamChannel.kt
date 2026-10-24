package com.sysdvr.tv

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

interface PacketSink {
    /** Se llama en el hilo de red; [data] se reutiliza al volver. */
    fun onPacket(header: Protocol.Header, data: ByteArray, length: Int)
    fun onStreamReset() {}
}

/**
 * Una conexión TCP Bridge (un socket por stream) con handshake propio y
 * reconexión automática hasta que se llame a [stop].
 */
class StreamChannel(
    private val host: String,
    private val stream: Protocol.Stream,
    private val sink: PacketSink,
    private val onStatus: (Protocol.Stream, Status, String) -> Unit,
) {
    enum class Status { CONNECTING, STREAMING, ERROR }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    private val header = ByteArray(Protocol.HEADER_SIZE)
    private val payload = ByteArray(Protocol.MAX_PAYLOAD)

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "sysdvr-${stream.label}").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        closeSocket()
        thread?.interrupt()
        thread?.join(1500)
        thread = null
    }

    /** Fuerza una reconexión inmediata (botón "Reconectar"). */
    fun reconnect() = closeSocket()

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun runLoop() {
        var attempt = 0
        while (running) {
            try {
                attempt++
                onStatus(stream, Status.CONNECTING, "Conectando a $host:${stream.port} (intento $attempt)")
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.receiveBufferSize = Protocol.MAX_PAYLOAD + Protocol.HEADER_SIZE
                s.connect(InetSocketAddress(host, stream.port), CONNECT_TIMEOUT_MS)
                s.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = s.getInputStream()

                handshake(s, input)
                s.soTimeout = READ_TIMEOUT_MS
                attempt = 0
                sink.onStreamReset()
                onStatus(stream, Status.STREAMING, "${stream.label} conectado")
                readLoop(input)
            } catch (e: Exception) {
                if (!running) break
                val msg = when (e) {
                    is SocketTimeoutException -> "sin datos (¿hay un juego abierto?)"
                    is ProtocolException -> e.message ?: "error de protocolo"
                    else -> e.message ?: e.javaClass.simpleName
                }
                Log.w(TAG, "${stream.label}: $msg", e)
                onStatus(stream, Status.ERROR, "${stream.label}: $msg. Reintentando…")
                closeSocket()
                sleepQuiet(RETRY_DELAY_MS)
            } finally {
                closeSocket()
            }
        }
    }

    private fun handshake(s: Socket, input: InputStream) {
        val hello = ByteArray(Protocol.HELLO_SIZE)
        readExact(input, hello, 0, hello.size)
        val version = Protocol.parseHello(hello)

        val out = s.getOutputStream()
        out.write(Protocol.buildRequest(stream, version))
        out.flush()

        val resp = ByteArray(Protocol.responseSize(version))
        readExact(input, resp, 0, resp.size)
        Protocol.checkResponse(resp)
    }

    private fun readLoop(input: InputStream) {
        var inSync = true
        while (running) {
            if (inSync) {
                readExact(input, header, 0, Protocol.HEADER_SIZE)
            } else {
                resync(input)
                inSync = true
            }
            val h = Protocol.parseHeader(header)
            if (h == null) {
                inSync = false
                continue
            }
            if (h.dataSize > 0) readExact(input, payload, 0, h.dataSize)
            if (h.isError) {
                Log.w(TAG, "${stream.label}: la consola reportó un error de captura")
                continue
            }
            sink.onPacket(h, payload, h.dataSize)
        }
    }

    /** Descarta bytes hasta encontrar CCCCCCCC y lee el resto del header. */
    private fun resync(input: InputStream) {
        Log.d(TAG, "${stream.label}: resincronizando")
        var count = 0
        while (running && count < 4) {
            val b = input.read()
            if (b < 0) throw IOException("Conexión cerrada")
            count = if (b.toByte() == Protocol.MAGIC_BYTE) count + 1 else 0
        }
        for (i in 0 until 4) header[i] = Protocol.MAGIC_BYTE
        readExact(input, header, 4, Protocol.HEADER_SIZE - 4)
    }

    private fun readExact(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var o = off
        var n = len
        while (n > 0) {
            val r = input.read(buf, o, n)
            if (r < 0) throw IOException("Conexión cerrada por la consola")
            o += r
            n -= r
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    companion object {
        private const val TAG = "SysDVR"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val HANDSHAKE_TIMEOUT_MS = 5000
        // Sin juego abierto la consola no envía nada; evitar reconectar en bucle
        private const val READ_TIMEOUT_MS = 15000
        private const val RETRY_DELAY_MS = 1500L
    }
}

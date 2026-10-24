package com.sysdvr.tv

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo SysDVR (TCP Bridge), según protocol.md y sysmodule/source/modes/proto.h.
 * Todo en little endian, estructuras empaquetadas.
 */
object Protocol {
    const val VIDEO_PORT = 9911
    const val AUDIO_PORT = 9922
    const val DISCOVERY_PORT = 19999

    // Hello: "SysDVR|03\0"
    const val HELLO_SIZE = 10
    const val HELLO_PREFIX = "SysDVR|"
    val SUPPORTED_VERSIONS = setOf("02", "03")

    // ProtoHandshakeRequest (16 bytes)
    const val REQUEST_MAGIC = 0xAAAAAAAA.toInt()
    const val REQUEST_SIZE = 16
    const val META_VIDEO = 1 shl 0
    const val META_AUDIO = 1 shl 1
    const val VIDEO_USE_NAL_HASH = 1 shl 0
    const val VIDEO_INJECT_PPS_SPS = 1 shl 1
    const val VIDEO_NAL_HASH_ONLY_IDR = 1 shl 2
    const val DEFAULT_AUDIO_BATCHING = 3

    // Respuesta: u32 en v02, ProtoHandshakeResponse (72 bytes) en v03
    const val RESPONSE_SIZE_V02 = 4
    const val RESPONSE_SIZE_V03 = 72
    const val HANDSHAKE_OK = 6

    // PacketHeader (18 bytes)
    const val HEADER_SIZE = 18
    const val HEADER_MAGIC = 0xCCCCCCCC.toInt()
    const val MAGIC_BYTE = 0xCC.toByte()
    const val VBUF_SIZE = 0x54000
    const val ABUF_SIZE = 0x1000
    const val MAX_AUDIO_BATCHING = 5
    val MAX_PAYLOAD = maxOf(VBUF_SIZE, ABUF_SIZE * (1 + MAX_AUDIO_BATCHING))

    const val PKT_VIDEO = 1 shl 0
    const val PKT_AUDIO = 1 shl 1
    const val PKT_DATA = 1 shl 2
    const val PKT_REPLAY = 1 shl 3
    const val PKT_MULTI_NAL = 1 shl 4
    const val PKT_ERROR = 1 shl 5
    const val NO_SLOT = 0xFF

    // SPS/PPS fijos del encoder de la Switch (Client/Core/StreamInfo.cs)
    val SPS = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x0C, 0x20, 0xAC.toByte(), 0x2B, 0x40, 0x28,
        0x02, 0xDD.toByte(), 0x35, 0x01, 0x0D, 0x01, 0xE0.toByte(), 0x80.toByte()
    )
    val PPS = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())

    const val VIDEO_WIDTH = 1280
    const val VIDEO_HEIGHT = 720

    enum class Stream(val port: Int, val meta: Int, val label: String) {
        VIDEO(VIDEO_PORT, META_VIDEO, "Video"),
        AUDIO(AUDIO_PORT, META_AUDIO, "Audio"),
    }

    /** Devuelve la versión ("02"/"03") o lanza si el hello es inválido. */
    fun parseHello(data: ByteArray): String {
        val str = String(data, Charsets.US_ASCII)
        if (!str.startsWith(HELLO_PREFIX))
            throw ProtocolException("Hello inválido: la consola no respondió como SysDVR")
        if (data[HELLO_SIZE - 1] != 0.toByte())
            throw ProtocolException("Hello inválido (terminador)")
        val ver = str.substring(HELLO_PREFIX.length, HELLO_PREFIX.length + 2)
        if (ver !in SUPPORTED_VERSIONS)
            throw ProtocolException("Versión de protocolo $ver no soportada; usa SysDVR 6.x")
        return ver
    }

    fun buildRequest(stream: Stream, version: String): ByteArray {
        val b = ByteBuffer.allocate(REQUEST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(REQUEST_MAGIC)
        // El sysmodule compara con memcmp: los dos caracteres ASCII en orden
        b.put(version[0].code.toByte())
        b.put(version[1].code.toByte())
        b.put(stream.meta.toByte())
        b.put((VIDEO_USE_NAL_HASH or VIDEO_NAL_HASH_ONLY_IDR or VIDEO_INJECT_PPS_SPS).toByte())
        b.put(DEFAULT_AUDIO_BATCHING.toByte())
        b.put(0) // FeatureFlags
        // Reserved[6] ya en cero
        return b.array()
    }

    fun responseSize(version: String) = if (version >= "03") RESPONSE_SIZE_V03 else RESPONSE_SIZE_V02

    fun checkResponse(data: ByteArray) {
        val code = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (code == HANDSHAKE_OK) return
        throw ProtocolException(
            when (code) {
                1 -> "La consola rechazó la versión del protocolo (actualiza SysDVR)"
                7 -> "Canal inválido: ¿SysDVR está en modo TCP Bridge?"
                else -> "Handshake rechazado (código $code)"
            }
        )
    }

    class Header(
        val dataSize: Int,
        val timestampUs: Long,
        val meta: Int,
        val replaySlot: Int,
    ) {
        val isVideo get() = meta and PKT_VIDEO != 0
        val isAudio get() = meta and PKT_AUDIO != 0
        val isReplay get() = meta and PKT_REPLAY != 0
        val isError get() = meta and PKT_ERROR != 0
    }

    /** null si el header no es válido (requiere resincronizar). */
    fun parseHeader(h: ByteArray): Header? {
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != HEADER_MAGIC) return null
        val size = b.getInt(4)
        if (size < 0 || size > MAX_PAYLOAD) return null
        return Header(size, b.getLong(8), h[16].toInt() and 0xFF, h[17].toInt() and 0xFF)
    }

    /** Descriptor de dispositivo: "SysDVR|6.3|03|SERIAL" (UDP puede venir con NULs de relleno). */
    data class DeviceInfo(val version: String, val protocol: String, val serial: String, val ip: String)

    fun parseBeacon(data: ByteArray, len: Int, ip: String): DeviceInfo? {
        var end = 0
        while (end < len && data[end] != 0.toByte()) end++
        val parts = String(data, 0, end, Charsets.UTF_8).split('|')
        if (parts.size < 4 || parts[0] != "SysDVR" || parts[2].length != 2) return null
        return DeviceInfo(parts[1], parts[2], parts[3].trim(), ip)
    }
}

class ProtocolException(msg: String) : Exception(msg)

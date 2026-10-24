package com.sysdvr.tv

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodificación H.264 por hardware con MediaCodec en modo asíncrono.
 * Cada frame decodificado se renderiza de inmediato: no se acumula nada.
 */
class VideoDecoder(private val surface: Surface) : PacketSink {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private val freeInputs = LinkedBlockingQueue<Int>()
    private val replayTable = arrayOfNulls<ByteArray>(256)
    @Volatile private var broken = false

    @Volatile var decoderName: String = "?"
        private set
    @Volatile var framesRendered = 0L
        private set
    @Volatile var framesDropped = 0L
        private set

    fun start() = synchronized(lock) { createCodec() }

    fun release() = synchronized(lock) { releaseCodec() }

    private fun createCodec() {
        releaseCodec()
        val name = findHardwareDecoder()
        val c = if (name != null) MediaCodec.createByCodecName(name)
        else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoderName = c.name

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, Protocol.VIDEO_WIDTH, Protocol.VIDEO_HEIGHT
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(Protocol.SPS))
            setByteBuffer("csd-1", ByteBuffer.wrap(Protocol.PPS))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Protocol.VBUF_SIZE)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // Claves de vendor para decoders que no exponen KEY_LOW_LATENCY
            setInteger("vendor.low-latency.enable", 1)
        }

        val ht = HandlerThread("sysdvr-codec", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        ht.start()
        callbackThread = ht
        freeInputs.clear()
        broken = false

        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                freeInputs.offer(index)
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    mc.releaseOutputBuffer(index, info.size > 0)
                    framesRendered++
                } catch (e: IllegalStateException) {
                    // codec liberado mientras llegaba el callback
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Error de MediaCodec", e)
                broken = true
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Formato de salida: $format")
            }
        }, Handler(ht.looper))

        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "Decoder: ${c.name}")
    }

    private fun releaseCodec() {
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
        callbackThread?.quitSafely()
        callbackThread = null
        freeInputs.clear()
    }

    override fun onStreamReset() {
        // Tras reconectar los slots de replay de la consola se reinician
        replayTable.fill(null)
    }

    override fun onPacket(header: Protocol.Header, data: ByteArray, length: Int) {
        if (!header.isVideo) return

        var src = data
        var len = length
        val slot = header.replaySlot
        if (header.isReplay) {
            val cached = replayTable[slot] ?: return
            src = cached
            len = cached.size
        } else if (slot != Protocol.NO_SLOT) {
            replayTable[slot] = data.copyOf(length)
        }
        if (len == 0) return

        synchronized(lock) {
            if (broken) {
                Log.w(TAG, "Recreando decoder")
                try { createCodec() } catch (e: Exception) { Log.e(TAG, "No se pudo recrear", e); return }
            }
            val c = codec ?: return
            // Si el decoder no libera un buffer a tiempo se descarta el paquete
            // en vez de encolarlo: latencia acotada.
            val index = freeInputs.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
            if (index == null) {
                framesDropped++
                return
            }
            try {
                val buf = c.getInputBuffer(index) ?: return
                buf.clear()
                if (len > buf.capacity()) {
                    c.queueInputBuffer(index, 0, 0, header.timestampUs, 0)
                    framesDropped++
                    return
                }
                buf.put(src, 0, len)
                c.queueInputBuffer(index, 0, len, header.timestampUs, 0)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "queueInputBuffer falló", e)
                broken = true
            }
        }
    }

    private fun findHardwareDecoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = list.codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val hw = candidates.filter(::isHardware)
        // Preferir el que declare baja latencia
        val lowLatency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hw.firstOrNull {
            it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        } else null
        return (lowLatency ?: hw.firstOrNull())?.name
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("sw"))
    }

    companion object {
        private const val TAG = "SysDVR"
        private const val INPUT_WAIT_MS = 50L
    }
}

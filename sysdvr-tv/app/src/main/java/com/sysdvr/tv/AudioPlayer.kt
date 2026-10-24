package com.sysdvr.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * PCM 16 bits, 48 kHz, estéreo con AudioTrack en modo baja latencia.
 * Escritura no bloqueante: si el buffer está lleno se descarta el excedente
 * para que la latencia de audio no crezca con el tiempo.
 */
class AudioPlayer : PacketSink {
    private var track: AudioTrack? = null

    fun start() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        // ~4 paquetes de captura (21 ms c/u) de margen para absorber jitter de red
        val bufSize = maxOf(minBuf * 2, Protocol.ABUF_SIZE * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.play()
        track = t
        Log.i(TAG, "AudioTrack listo, buffer=$bufSize bytes (min $minBuf)")
    }

    fun release() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    override fun onStreamReset() {
        track?.let {
            try { it.pause(); it.flush(); it.play() } catch (_: Exception) {}
        }
    }

    override fun onPacket(header: Protocol.Header, data: ByteArray, length: Int) {
        if (!header.isAudio || length <= 0) return
        val t = track ?: return
        // Alinear a frames completos (4 bytes)
        val len = length and 3.inv()
        t.write(data, 0, len, AudioTrack.WRITE_NON_BLOCKING)
    }

    companion object {
        private const val TAG = "SysDVR"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

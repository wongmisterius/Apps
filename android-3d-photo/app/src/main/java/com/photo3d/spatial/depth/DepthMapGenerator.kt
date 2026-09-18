package com.photo3d.spatial.depth

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.photo3d.spatial.data.DepthGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Estima un mapa de profundidad aproximado a partir de una sola foto.
 *
 * No hay un sensor LiDAR como en el iPhone, así que se aproxima la profundidad
 * separando sujeto (cerca) de fondo (lejos) con ML Kit Selfie Segmentation y
 * suavizando el resultado para lograr una transición gradual en vez de un
 * "recorte" duro entre planos. Funciona mejor con fotos de personas/retratos;
 * para escenas sin sujeto claro cae a una malla plana (sin efecto de paralaje).
 */
object DepthMapGenerator {

    private const val GRID = 64

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
        )
    }

    suspend fun generate(bitmap: Bitmap): DepthGrid {
        return try {
            val mask = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            withContext(Dispatchers.Default) {
                val buffer = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val maskWidth = mask.width
                val maskHeight = mask.height

                val raw = FloatArray(GRID * GRID)
                for (gy in 0 until GRID) {
                    for (gx in 0 until GRID) {
                        val mx = (gx * (maskWidth - 1) / (GRID - 1).toFloat()).toInt().coerceIn(0, maskWidth - 1)
                        val my = (gy * (maskHeight - 1) / (GRID - 1).toFloat()).toInt().coerceIn(0, maskHeight - 1)
                        raw[gy * GRID + gx] = readConfidence(buffer, mx, my, maskWidth)
                    }
                }
                val smoothed = smooth(raw, GRID, GRID, passes = 3)
                DepthGrid(GRID, GRID, normalize(smoothed))
            }
        } catch (t: Throwable) {
            // Fallback: sin sujeto detectable, la foto se muestra plana (sin paralaje).
            DepthGrid.flat(GRID, GRID)
        }
    }

    private fun readConfidence(buffer: FloatBuffer, x: Int, y: Int, width: Int): Float {
        val index = y * width + x
        return buffer.get(index)
    }

    /** Suavizado tipo box-blur para evitar bordes duros entre primer plano y fondo. */
    private fun smooth(values: FloatArray, w: Int, h: Int, passes: Int): FloatArray {
        var current = values
        repeat(passes) {
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                sum += current[ny * w + nx]
                                count++
                            }
                        }
                    }
                    out[y * w + x] = sum / count
                }
            }
            current = out
        }
        return current
    }

    private fun normalize(values: FloatArray): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in values) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        val range = (hi - lo).takeIf { it > 1e-4f } ?: return values
        return FloatArray(values.size) { (values[it] - lo) / range }
    }
}

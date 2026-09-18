package com.photo3d.spatial.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Mapa de profundidad de baja resolución: un valor por vértice de la malla 3D.
 * value = 0f (fondo, lejos) .. 1f (primer plano, cerca de la cámara).
 */
class DepthGrid(val width: Int, val height: Int, val values: FloatArray) {

    init {
        require(values.size == width * height) { "El tamaño de values no coincide con width*height" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    fun saveTo(file: File) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(width)
            out.writeInt(height)
            for (v in values) out.writeFloat(v)
        }
    }

    companion object {
        fun loadFrom(file: File): DepthGrid {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val w = input.readInt()
                val h = input.readInt()
                val values = FloatArray(w * h) { input.readFloat() }
                return DepthGrid(w, h, values)
            }
        }

        /** Malla plana (todo a la misma profundidad) usada como fallback si la segmentación falla. */
        fun flat(width: Int, height: Int): DepthGrid = DepthGrid(width, height, FloatArray(width * height))
    }
}

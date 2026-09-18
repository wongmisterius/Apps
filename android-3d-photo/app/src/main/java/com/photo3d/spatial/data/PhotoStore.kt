package com.photo3d.spatial.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

data class Photo3DEntry(val id: String, val imageFile: File, val depthFile: File) {
    val createdAtMillis: Long get() = id.toLongOrNull() ?: 0L
}

/**
 * Guarda cada foto 3D como dos archivos en el almacenamiento privado de la app:
 *  - <id>.jpg    imagen a resolución de visualización
 *  - <id>.depth  grilla de profundidad binaria (ver DepthGrid)
 */
class PhotoStore(context: Context) {

    private val dir: File = File(context.filesDir, "photos3d").apply { mkdirs() }

    fun newId(): String = System.currentTimeMillis().toString()

    fun save(id: String, bitmap: Bitmap, depth: DepthGrid): Photo3DEntry {
        val imageFile = File(dir, "$id.jpg")
        val depthFile = File(dir, "$id.depth")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        depth.saveTo(depthFile)
        return Photo3DEntry(id, imageFile, depthFile)
    }

    fun list(): List<Photo3DEntry> {
        val images = dir.listFiles { f -> f.extension == "jpg" } ?: emptyArray()
        return images
            .mapNotNull { img ->
                val id = img.nameWithoutExtension
                val depthFile = File(dir, "$id.depth")
                if (depthFile.exists()) Photo3DEntry(id, img, depthFile) else null
            }
            .sortedByDescending { it.createdAtMillis }
    }

    fun get(id: String): Photo3DEntry? {
        val img = File(dir, "$id.jpg")
        val depthFile = File(dir, "$id.depth")
        return if (img.exists() && depthFile.exists()) Photo3DEntry(id, img, depthFile) else null
    }

    fun delete(entry: Photo3DEntry) {
        entry.imageFile.delete()
        entry.depthFile.delete()
    }

    fun loadBitmap(entry: Photo3DEntry): Bitmap =
        BitmapFactory.decodeFile(entry.imageFile.absolutePath)

    fun loadDepth(entry: Photo3DEntry): DepthGrid = DepthGrid.loadFrom(entry.depthFile)
}

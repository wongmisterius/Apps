package com.photo3d.spatial.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.photo3d.spatial.data.Photo3DEntry
import com.photo3d.spatial.data.PhotoStore

@Composable
fun GalleryScreen(photoStore: PhotoStore, onOpenPhoto: (String) -> Unit) {
    val entries = remember { photoStore.list() }

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Todavía no sacaste ninguna foto 3D.\nTocá el botón de cámara para empezar.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(entries, key = { it.id }) { entry: Photo3DEntry ->
            val thumbnail = remember(entry.id) {
                BitmapFactory.decodeFile(entry.imageFile.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = 4
                })
            }
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenPhoto(entry.id) }
            )
        }
    }
}

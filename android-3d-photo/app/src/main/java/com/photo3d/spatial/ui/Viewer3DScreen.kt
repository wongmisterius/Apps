package com.photo3d.spatial.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.photo3d.spatial.data.PhotoStore
import com.photo3d.spatial.gl.ParallaxGLSurfaceView
import com.photo3d.spatial.sensors.TiltSensorManager

/** Muestra la foto con el efecto de paralaje 3D reaccionando a la inclinación del teléfono. */
@Composable
fun Viewer3DScreen(photoStore: PhotoStore, photoId: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val glView = remember { ParallaxGLSurfaceView(context) }
    val tiltManager = remember {
        TiltSensorManager(context) { x, y -> glView.updateTilt(x, y) }
    }

    DisposableEffect(photoId) {
        val entry = photoStore.get(photoId)
        if (entry != null) {
            val bitmap = photoStore.loadBitmap(entry)
            val depth = photoStore.loadDepth(entry)
            glView.showPhoto(bitmap, depth)
        }
        onDispose { }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView.onResume()
                    tiltManager.start()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    tiltManager.stop()
                    glView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        tiltManager.start()
        onDispose {
            tiltManager.stop()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
}

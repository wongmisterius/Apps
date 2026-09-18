package com.photo3d.spatial.ui

import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.photo3d.spatial.camera.takePhotoAsBitmap
import com.photo3d.spatial.data.PhotoStore
import com.photo3d.spatial.depth.DepthMapGenerator
import kotlinx.coroutines.launch

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(photoStore: PhotoStore, onPhotoSaved: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                        imageCapture = capture
                    } catch (e: Exception) {
                        Log.e(TAG, "No se pudo iniciar la cámara", e)
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Text(
            text = "Apuntá a un sujeto con fondo despejado para mejor efecto 3D",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 16.dp, end = 16.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(84.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
                .then(
                    if (isProcessing) Modifier else Modifier.clickable {
                        val capture = imageCapture ?: return@clickable
                        isProcessing = true
                        scope.launch {
                            try {
                                val bitmap = capture.takePhotoAsBitmap(context)
                                val depth = DepthMapGenerator.generate(bitmap)
                                val id = photoStore.newId()
                                photoStore.save(id, bitmap, depth)
                                onPhotoSaved(id)
                            } catch (t: Throwable) {
                                Log.e(TAG, "Error al capturar/procesar foto", t)
                                Toast.makeText(context, "No se pudo procesar la foto", Toast.LENGTH_SHORT).show()
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isProcessing) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

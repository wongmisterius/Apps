package com.photo3d.spatial

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.photo3d.spatial.data.PhotoStore
import com.photo3d.spatial.ui.CameraScreen
import com.photo3d.spatial.ui.GalleryScreen
import com.photo3d.spatial.ui.Viewer3DScreen

private const val ROUTE_CAMERA = "camera"
private const val ROUTE_GALLERY = "gallery"
private const val ROUTE_VIEWER = "viewer/{photoId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Photo3DApp()
            }
        }
    }
}

@Composable
private fun Photo3DApp() {
    val context = LocalContext.current
    val photoStore = remember { PhotoStore(context) }
    val navController = rememberNavController()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Se necesita permiso de cámara para tomar fotos 3D.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Conceder permiso")
                }
            }
        }
        return
    }

    Photo3DNavHost(navController, photoStore)
}

@Composable
private fun Photo3DNavHost(navController: NavHostController, photoStore: PhotoStore) {
    NavHost(navController = navController, startDestination = ROUTE_CAMERA) {
        composable(ROUTE_CAMERA) {
            Box(Modifier.fillMaxSize()) {
                CameraScreen(photoStore = photoStore) { id ->
                    navController.navigate("viewer/$id")
                }
                FilledTonalButton(
                    onClick = { navController.navigate(ROUTE_GALLERY) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                ) {
                    Text("Galería")
                }
            }
        }
        composable(ROUTE_GALLERY) {
            Box(Modifier.fillMaxSize()) {
                GalleryScreen(photoStore = photoStore) { id ->
                    navController.navigate("viewer/$id")
                }
                FilledTonalButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                ) {
                    Text("< Volver")
                }
            }
        }
        composable(ROUTE_VIEWER) { backStackEntry ->
            val photoId = backStackEntry.arguments?.getString("photoId") ?: return@composable
            Box(Modifier.fillMaxSize()) {
                Viewer3DScreen(photoStore = photoStore, photoId = photoId)
                FilledTonalButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                ) {
                    Text("< Volver")
                }
            }
        }
    }
}

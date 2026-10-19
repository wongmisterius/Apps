package com.wongmisterius.escanerred

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.wongmisterius.escanerred.ui.EscanerViewModel
import com.wongmisterius.escanerred.ui.PantallaPrincipal
import com.wongmisterius.escanerred.ui.TemaEscaner

class MainActivity : ComponentActivity() {

    private val vm: EscanerViewModel by viewModels()

    // La ubicación solo se usa para que Android entregue el nombre (SSID) de la red
    private val permisoUbicacion =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.actualizarRed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permisoUbicacion.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        setContent {
            TemaEscaner { PantallaPrincipal(vm) }
        }
    }
}

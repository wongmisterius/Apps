package com.wongmisterius.escanerred.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wongmisterius.escanerred.data.DispositivoRegistrado
import com.wongmisterius.escanerred.net.FuenteMac
import com.wongmisterius.escanerred.net.InfoFabricante
import com.wongmisterius.escanerred.net.TipoMac
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPrincipal(vm: EscanerViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var pestana by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            vm.mensajeMostrado()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Escáner de Red") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (pestana == 0) {
                ExtendedFloatingActionButton(
                    onClick = { if (!estado.escaneando) vm.escanear() },
                    icon = { Icon(Icons.Filled.Search, null) },
                    text = { Text(if (estado.escaneando) "Escaneando…" else "Escanear") }
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = vm::nuevoManual,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Agregar") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = pestana) {
                Tab(pestana == 0, { pestana = 0 }, text = { Text("Red (${estado.resultados.size})") })
                Tab(pestana == 1, { pestana = 1 }, text = { Text("Registrados (${estado.registrados.size})") })
            }
            when (pestana) {
                0 -> PestanaRed(estado, vm)
                else -> PestanaRegistrados(estado.registrados, vm)
            }
        }
    }

    estado.edicion?.let { DialogoEdicion(it, vm) }
}

@Composable
private fun PestanaRed(estado: EstadoUi, vm: EscanerViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { TarjetaRed(estado) }
        if (estado.resultados.isNotEmpty() && estado.resultados.none { it.equipo.mac != null && !it.equipo.esEsteDispositivo }) {
            item { AvisoSinMac() }
        }
        items(estado.resultados, key = { it.equipo.ip }) { f ->
            TarjetaEquipo(f, onClick = { vm.editarDesdeEscaneo(f) })
        }
    }
}

@Composable
private fun TarjetaRed(estado: EstadoUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    estado.red?.ssid ?: if (estado.red != null) "WiFi (SSID oculto: concede ubicación)" else "Sin WiFi",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            estado.red?.let { r ->
                Text("Mi IP: ${r.ipLocal}   Red: ${r.red}", style = MaterialTheme.typography.bodyMedium)
                r.gateway?.let { Text("Router: $it", style = MaterialTheme.typography.bodyMedium) }
            }
            estado.ultimoEscaneo?.let {
                Text(
                    "Último escaneo: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (estado.escaneando) {
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(progress = { estado.progreso }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AvisoSinMac() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            "Android 10+ impide a las apps leer la tabla ARP, por eso no siempre se obtiene la MAC. " +
                "Se consigue por NetBIOS (PCs Windows, NAS, Samba). Para el resto, toca el equipo y escribe " +
                "la MAC que muestra tu router: la app identificará el fabricante y si es aleatoria.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TarjetaEquipo(f: FilaEquipo, onClick: () -> Unit) {
    val e = f.equipo
    val icono: ImageVector = when {
        e.esGateway -> Icons.Filled.Router
        e.esEsteDispositivo -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.Devices
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, null, Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    f.registrado?.nombre ?: e.hostname ?: when {
                        e.esEsteDispositivo -> "Este dispositivo"
                        e.esGateway -> "Router"
                        else -> "Desconocido"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(e.ip, style = MaterialTheme.typography.bodyMedium)
                val mac = e.mac ?: f.registrado?.mac
                Text(
                    mac?.let { it + (e.fuenteMac?.let { s -> "  (${s.etiqueta()})" } ?: "  (registrada)") }
                        ?: "MAC no disponible",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                val info = if (e.mac != null) f.fabricante else null
                LineaFabricante(info, f.registrado?.fabricante)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (f.registrado != null) Etiqueta("Registrado", Icons.Filled.Star)
                    val tipo = info?.tipoMac ?: if (f.registrado?.macAleatoria == true) TipoMac.ALEATORIA else null
                    if (tipo == TipoMac.ALEATORIA || tipo == TipoMac.LOCAL_ELI) Etiqueta("MAC aleatoria", Icons.Filled.Shuffle)
                }
            }
        }
    }
}

@Composable
private fun LineaFabricante(info: InfoFabricante?, fabricanteGuardado: String?) {
    val texto = when (info?.tipoMac) {
        TipoMac.ALEATORIA -> "Fabricante oculto (MAC privada)"
        TipoMac.LOCAL_ELI -> "CID: ${info.fabricante} (MAC local)"
        TipoMac.GLOBAL -> info.fabricante ?: "Fabricante no registrado en la IEEE"
        TipoMac.MULTICAST, TipoMac.INVALIDA -> "MAC no válida"
        null -> fabricanteGuardado
    } ?: return
    Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Etiqueta(texto: String, icono: ImageVector) {
    AssistChip(
        onClick = {},
        label = { Text(texto) },
        leadingIcon = { Icon(icono, null, Modifier.size(AssistChipDefaults.IconSize)) }
    )
}

private fun FuenteMac.etiqueta() = when (this) {
    FuenteMac.ARP -> "ARP"
    FuenteMac.NETBIOS -> "NetBIOS"
    FuenteMac.MANUAL -> "manual"
}

@Composable
private fun PestanaRegistrados(lista: List<DispositivoRegistrado>, vm: EscanerViewModel) {
    if (lista.isEmpty()) {
        Text(
            "Aún no hay dispositivos registrados. Escanea la red y toca un equipo para ponerle nombre.",
            Modifier.padding(24.dp)
        )
        return
    }
    val formato = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(lista, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().clickable { vm.editarRegistrado(r) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.nombre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(r.mac ?: "Sin MAC", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(
                            when {
                                r.macAleatoria -> "MAC aleatoria — fabricante oculto"
                                r.fabricante != null -> r.fabricante
                                r.mac != null -> "Fabricante desconocido"
                                else -> "—"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            listOfNotNull(r.ultimaIp, r.visto?.let { "visto ${formato.format(Date(it))}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { vm.eliminar(r) }) { Icon(Icons.Filled.Delete, "Eliminar") }
                }
            }
        }
    }
}

@Composable
private fun DialogoEdicion(e: Edicion, vm: EscanerViewModel) {
    val info = vm.fabricanteDe(e.mac.ifBlank { null })
    var confirmarBorrado by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = vm::cancelarEdicion,
        title = { Text(if (e.id == 0L) "Registrar dispositivo" else "Editar dispositivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(e.ip?.let { "IP: $it" }, e.hostname?.let { "Host: $it" }).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = e.nombre,
                    onValueChange = { vm.cambiarEdicion(e.copy(nombre = it)) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                OutlinedTextField(
                    value = e.mac,
                    onValueChange = { vm.cambiarEdicion(e.copy(mac = it)) },
                    label = { Text("MAC (opcional)") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )
                if (e.mac.isNotBlank()) {
                    Text(info.tipoMac.descripcion, style = MaterialTheme.typography.bodySmall)
                    when (info.tipoMac) {
                        TipoMac.GLOBAL -> Text(
                            "Fabricante: ${info.fabricante ?: "no encontrado"}",
                            color = MaterialTheme.colorScheme.primary
                        )
                        TipoMac.LOCAL_ELI -> Text(
                            "CID registrado por: ${info.fabricante}. Puede seguir siendo aleatoria.",
                            color = MaterialTheme.colorScheme.primary
                        )
                        TipoMac.ALEATORIA -> Text(
                            "El fabricante no se puede deducir. En Android/iOS desactiva \"MAC aleatoria\" " +
                                "(Dirección privada) en los ajustes de esta red para ver la MAC real.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> {}
                    }
                }
                OutlinedTextField(
                    value = e.notas,
                    onValueChange = { vm.cambiarEdicion(e.copy(notas = it)) },
                    label = { Text("Notas") },
                    minLines = 2
                )
                e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = vm::guardarEdicion) { Text("Guardar") } },
        dismissButton = {
            Row {
                if (e.id != 0L) {
                    TextButton(onClick = { confirmarBorrado = true }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = vm::cancelarEdicion) { Text("Cancelar") }
            }
        }
    )

    if (confirmarBorrado) {
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            title = { Text("¿Eliminar \"${e.nombre}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarBorrado = false
                    vm.estado.value.registrados.firstOrNull { it.id == e.id }?.let(vm::eliminar)
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } }
        )
    }
}

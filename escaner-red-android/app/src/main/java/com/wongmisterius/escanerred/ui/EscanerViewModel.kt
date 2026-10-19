package com.wongmisterius.escanerred.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wongmisterius.escanerred.data.DispositivoRegistrado
import com.wongmisterius.escanerred.data.DispositivosDb
import com.wongmisterius.escanerred.net.EquipoDetectado
import com.wongmisterius.escanerred.net.InfoFabricante
import com.wongmisterius.escanerred.net.InfoRed
import com.wongmisterius.escanerred.net.MacUtils
import com.wongmisterius.escanerred.net.NetworkScanner
import com.wongmisterius.escanerred.net.OuiDatabase
import com.wongmisterius.escanerred.net.TipoMac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilaEquipo(
    val equipo: EquipoDetectado,
    val fabricante: InfoFabricante,
    val registrado: DispositivoRegistrado?
)

data class Edicion(
    val id: Long = 0,
    val nombre: String = "",
    val mac: String = "",
    val notas: String = "",
    val ip: String? = null,
    val hostname: String? = null,
    val creado: Long = System.currentTimeMillis(),
    val visto: Long? = null,
    val error: String? = null
)

data class EstadoUi(
    val red: InfoRed? = null,
    val escaneando: Boolean = false,
    val progreso: Float = 0f,
    val resultados: List<FilaEquipo> = emptyList(),
    val registrados: List<DispositivoRegistrado> = emptyList(),
    val ultimoEscaneo: Long? = null,
    val edicion: Edicion? = null,
    val mensaje: String? = null
)

class EscanerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DispositivosDb(app)
    private val scanner = NetworkScanner(app)
    private var oui: OuiDatabase? = null

    private val _estado = MutableStateFlow(EstadoUi())
    val estado: StateFlow<EstadoUi> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { oui = OuiDatabase.get(app) }
            recargarRegistrados()
        }
        actualizarRed()
    }

    fun actualizarRed() {
        _estado.update { it.copy(red = runCatching { scanner.infoRed() }.getOrNull()) }
    }

    fun fabricanteDe(mac: String?): InfoFabricante =
        oui?.consultar(mac) ?: InfoFabricante(null, MacUtils.clasificar(mac))

    fun escanear() {
        if (_estado.value.escaneando) return
        actualizarRed()
        val red = _estado.value.red ?: run {
            _estado.update { it.copy(mensaje = "No hay conexión WiFi activa") }
            return
        }
        _estado.update { it.copy(escaneando = true, progreso = 0f, resultados = emptyList()) }
        viewModelScope.launch {
            try {
                val equipos = scanner.escanear(red) { hecho, total ->
                    _estado.update { it.copy(progreso = hecho.toFloat() / total) }
                }
                val ahora = System.currentTimeMillis()
                val registrados = withContext(Dispatchers.IO) { db.todos() }
                val filas = equipos.map { e ->
                    FilaEquipo(e, fabricanteDe(e.mac), emparejar(e, registrados))
                }
                withContext(Dispatchers.IO) {
                    db.marcarVistos(filas.mapNotNull { f -> f.registrado?.let { Triple(it.id, f.equipo.ip, f.equipo.hostname) } }, ahora)
                }
                _estado.update { it.copy(resultados = filas, ultimoEscaneo = ahora) }
                recargarRegistrados()
            } catch (e: Exception) {
                _estado.update { it.copy(mensaje = "Error al escanear: ${e.message}") }
            } finally {
                _estado.update { it.copy(escaneando = false) }
            }
        }
    }

    /** MAC exacta; si el registro no tiene MAC, por hostname o última IP. */
    private fun emparejar(e: EquipoDetectado, registrados: List<DispositivoRegistrado>): DispositivoRegistrado? {
        e.mac?.let { mac -> registrados.firstOrNull { it.mac == mac }?.let { return it } }
        val sinMac = registrados.filter { it.mac == null }
        e.hostname?.let { h -> sinMac.firstOrNull { it.hostname.equals(h, true) }?.let { return it } }
        return sinMac.firstOrNull { it.ultimaIp == e.ip }
    }

    private suspend fun recargarRegistrados() {
        val lista = withContext(Dispatchers.IO) { db.todos() }
        _estado.update { s ->
            s.copy(
                registrados = lista,
                resultados = s.resultados.map { f ->
                    f.copy(registrado = f.registrado?.let { r -> lista.firstOrNull { it.id == r.id } }
                        ?: emparejar(f.equipo, lista))
                }
            )
        }
    }

    // ---- Edición ----

    fun editarDesdeEscaneo(f: FilaEquipo) {
        val r = f.registrado
        _estado.update {
            it.copy(
                edicion = if (r != null) edicionDe(r).copy(ip = f.equipo.ip, hostname = f.equipo.hostname ?: r.hostname)
                else Edicion(
                    nombre = f.equipo.hostname?.substringBefore('.') ?: "",
                    mac = f.equipo.mac ?: "",
                    ip = f.equipo.ip,
                    hostname = f.equipo.hostname
                )
            )
        }
    }

    fun editarRegistrado(r: DispositivoRegistrado) = _estado.update { it.copy(edicion = edicionDe(r)) }

    fun nuevoManual() = _estado.update { it.copy(edicion = Edicion()) }

    fun cambiarEdicion(e: Edicion) = _estado.update { it.copy(edicion = e.copy(error = null)) }

    fun cancelarEdicion() = _estado.update { it.copy(edicion = null) }

    private fun edicionDe(r: DispositivoRegistrado) = Edicion(
        id = r.id, nombre = r.nombre, mac = r.mac ?: "", notas = r.notas ?: "",
        ip = r.ultimaIp, hostname = r.hostname, creado = r.creado, visto = r.visto
    )

    fun guardarEdicion() {
        val e = _estado.value.edicion ?: return
        val nombre = e.nombre.trim()
        if (nombre.isEmpty()) return cambiarError("Escribe un nombre")
        val mac = if (e.mac.isBlank()) null else MacUtils.normalizar(e.mac)
        if (e.mac.isNotBlank() && (mac == null || !MacUtils.esUtilizable(mac))) {
            return cambiarError("MAC no válida (formato AA:BB:CC:DD:EE:FF)")
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                if (mac != null && db.existeMac(mac, e.id)) return@withContext "Esa MAC ya está registrada"
                val info = fabricanteDe(mac)
                db.guardar(
                    DispositivoRegistrado(
                        id = e.id,
                        nombre = nombre,
                        mac = mac,
                        fabricante = info.fabricante,
                        macAleatoria = info.tipoMac == TipoMac.ALEATORIA,
                        ultimaIp = e.ip,
                        hostname = e.hostname,
                        notas = e.notas.trim().ifEmpty { null },
                        creado = e.creado,
                        visto = e.visto
                    )
                )
                null
            }
            if (error != null) {
                cambiarError(error)
            } else {
                _estado.update { it.copy(edicion = null, mensaje = "Guardado: $nombre") }
                recargarRegistrados()
            }
        }
    }

    fun eliminar(r: DispositivoRegistrado) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.eliminar(r.id) }
            _estado.update { s ->
                s.copy(
                    edicion = null,
                    mensaje = "Eliminado: ${r.nombre}",
                    resultados = s.resultados.map { if (it.registrado?.id == r.id) it.copy(registrado = null) else it }
                )
            }
            recargarRegistrados()
        }
    }

    fun mensajeMostrado() = _estado.update { it.copy(mensaje = null) }

    private fun cambiarError(msg: String) = _estado.update { s -> s.copy(edicion = s.edicion?.copy(error = msg)) }
}

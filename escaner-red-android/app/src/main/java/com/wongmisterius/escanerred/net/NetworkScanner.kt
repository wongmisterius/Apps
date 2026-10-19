package com.wongmisterius.escanerred.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class InfoRed(
    val ssid: String?,
    val ipLocal: String,
    val prefijo: Int,
    val gateway: String?,
    val interfaz: String?
) {
    val red: String get() = "${Ipv4.aTexto(Ipv4.aEntero(ipLocal) and Ipv4.mascara(prefijo))}/$prefijo"
}

enum class FuenteMac { ARP, NETBIOS, MANUAL }

data class EquipoDetectado(
    val ip: String,
    val mac: String? = null,
    val fuenteMac: FuenteMac? = null,
    val hostname: String? = null,
    val esEsteDispositivo: Boolean = false,
    val esGateway: Boolean = false
)

object Ipv4 {
    fun aEntero(ip: String): Int = ip.split('.').fold(0) { acc, p -> (acc shl 8) or p.toInt() }
    fun aTexto(v: Int): String = "${v ushr 24 and 0xFF}.${v ushr 16 and 0xFF}.${v ushr 8 and 0xFF}.${v and 0xFF}"
    fun mascara(prefijo: Int): Int = if (prefijo == 0) 0 else -1 shl (32 - prefijo)
}

class NetworkScanner(private val context: Context) {

    companion object {
        /** Puertos típicos: web, SSH, SMB, DNS, impresoras, iPhone (62078), Chromecast (8008/8009). */
        private val PUERTOS = intArrayOf(80, 443, 22, 445, 139, 53, 8080, 62078, 8008, 9100)
        private const val TIMEOUT_TCP = 250
        private const val PARALELO = 96
        /** Prefijo mínimo a barrer: redes más grandes se limitan a /22 (1022 hosts) alrededor de la IP local. */
        private const val PREFIJO_MINIMO = 22
    }

    @Suppress("DEPRECATION")
    fun infoRed(): InfoRed? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val red = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val lp = cm.getLinkProperties(red) ?: return null
        val dir = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress

        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val ssid = wm?.connectionInfo?.ssid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID || it == "<unknown ssid>" }

        return InfoRed(ssid, dir.address.hostAddress!!, dir.prefixLength, gateway, lp.interfaceName)
    }

    /**
     * Barre la subred: sondea cada IP (ICMP/TCP), luego consulta NetBIOS, mDNS y DNS inverso
     * de los equipos vivos, y por último lee la tabla ARP (solo funciona en Android 9 o
     * anterior, o en equipos con root/ROMs permisivas).
     */
    suspend fun escanear(
        info: InfoRed,
        onProgreso: (hecho: Int, total: Int) -> Unit
    ): List<EquipoDetectado> = withContext(Dispatchers.IO) {
        val prefijo = info.prefijo.coerceIn(PREFIJO_MINIMO, 30)
        val mascara = Ipv4.mascara(prefijo)
        val base = Ipv4.aEntero(info.ipLocal) and mascara
        val total = (mascara.inv()) - 1
        val hosts = (1..total).map { Ipv4.aTexto(base + it) }

        val hechos = AtomicInteger()
        val limite = Semaphore(PARALELO)

        // 1) Barrido de presencia
        val vivos = coroutineScope {
            hosts.map { ip ->
                async {
                    limite.withPermit {
                        val vivo = ip == info.ipLocal || estaVivo(ip)
                        onProgreso(hechos.incrementAndGet(), total)
                        ip.takeIf { vivo }
                    }
                }
            }.awaitAll().filterNotNull().toMutableSet()
        }

        // 2) La tabla ARP (si es legible) aporta MACs y equipos que no respondieron
        val arp = leerTablaArp()
        arp.keys.filter { ip -> Ipv4.aEntero(ip) and mascara == base }.forEach { vivos += it }

        // 3) Identificación de cada equipo vivo
        val equipos = coroutineScope {
            vivos.map { ip ->
                async {
                    limite.withPermit { identificar(ip, info, arp[ip]) }
                }
            }.awaitAll()
        }
        equipos.sortedBy { Ipv4.aEntero(it.ip).toUInt() }
    }

    private fun estaVivo(ip: String): Boolean {
        val dir = InetAddress.getByName(ip)
        // Fuerza también la resolución ARP del kernel aunque no podamos leerla
        if (runCatching { dir.isReachable(300) }.getOrDefault(false)) return true
        for (puerto in PUERTOS) {
            try {
                Socket().use { it.connect(InetSocketAddress(dir, puerto), TIMEOUT_TCP) }
                return true
            } catch (_: ConnectException) {
                // RST (conexión rechazada) = el host existe
                return true
            } catch (_: Exception) {
                // timeout o inalcanzable: probar siguiente puerto
            }
        }
        return false
    }

    private suspend fun identificar(ip: String, info: InfoRed, macArp: String?): EquipoDetectado = coroutineScope {
        val nb = async { NetBios.consultar(ip) }
        val mdns = async { Mdns.nombreInverso(ip) }
        val dns = async {
            withTimeoutOrNull(1500) {
                runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip }
            }
        }
        val respuestaNb = nb.await()
        val mac = macArp ?: respuestaNb?.mac
        EquipoDetectado(
            ip = ip,
            mac = mac,
            fuenteMac = when {
                macArp != null -> FuenteMac.ARP
                respuestaNb?.mac != null -> FuenteMac.NETBIOS
                else -> null
            },
            hostname = respuestaNb?.nombre ?: mdns.await() ?: dns.await(),
            esEsteDispositivo = ip == info.ipLocal,
            esGateway = ip == info.gateway
        )
    }

    /** IP -> MAC desde /proc/net/arp o "ip neigh". Vacío en Android 10+ sin root. */
    private fun leerTablaArp(): Map<String, String> {
        val tabla = HashMap<String, String>()
        val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { linea ->
                val c = linea.trim().split(Regex("\\s+"))
                if (c.size >= 4 && c[0].matches(ipv4) && c[2] != "0x0" && MacUtils.esUtilizable(c[3])) {
                    tabla[c[0]] = MacUtils.normalizar(c[3])!!
                }
            }
        }
        if (tabla.isEmpty()) runCatching {
            val p = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readLines().forEach { linea ->
                val c = linea.trim().split(Regex("\\s+"))
                val i = c.indexOf("lladdr")
                if (i > 0 && i + 1 < c.size && c[0].matches(ipv4) && MacUtils.esUtilizable(c[i + 1]) && !linea.contains("FAILED")) {
                    tabla[c[0]] = MacUtils.normalizar(c[i + 1])!!
                }
            }
            p.waitFor()
        }
        return tabla
    }
}

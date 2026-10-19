package com.wongmisterius.escanerred.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class RespuestaNetBios(val nombre: String?, val mac: String?)

/**
 * Consulta NBSTAT (NetBIOS Node Status, UDP 137). Windows, Samba, NAS y muchas
 * impresoras responden con su nombre y su MAC: es la única vía sin root para
 * obtener la MAC de otro equipo en Android 10+.
 */
object NetBios {

    private val CONSULTA: ByteArray = byteArrayOf(
        0x13, 0x37,             // id de transacción
        0x00, 0x00,             // flags
        0x00, 0x01,             // 1 pregunta
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x20                    // longitud del nombre codificado
    ) + "CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toByteArray(Charsets.US_ASCII) + byteArrayOf(
        0x00,
        0x00, 0x21,             // tipo NBSTAT
        0x00, 0x01              // clase IN
    )

    fun consultar(ip: String, timeoutMs: Int = 700): RespuestaNetBios? = try {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(CONSULTA, CONSULTA.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            parsear(buf, p.length)
        }
    } catch (_: SocketTimeoutException) {
        null
    } catch (_: Exception) {
        null
    }

    internal fun parsear(b: ByteArray, len: Int): RespuestaNetBios? {
        var i = 12
        if (len <= i) return null
        // Nombre de la respuesta: completo (0x20 + 32 + 0x00) o puntero comprimido
        i += if (b[i].toInt() and 0xC0 == 0xC0) 2 else (b[i].toInt() and 0xFF) + 2
        i += 2 + 2 + 4 + 2       // tipo, clase, TTL, longitud de datos
        if (len <= i) return null
        val cantidad = b[i].toInt() and 0xFF
        i++
        var nombre: String? = null
        repeat(cantidad) {
            if (i + 18 > len) return null
            val sufijo = b[i + 15].toInt() and 0xFF
            val esGrupo = b[i + 16].toInt() and 0x80 != 0
            if (nombre == null && sufijo == 0x00 && !esGrupo) {
                nombre = String(b, i, 15, Charsets.US_ASCII).trim()
            }
            i += 18
        }
        val mac = if (i + 6 <= len) {
            (0 until 6).joinToString(":") { "%02X".format(b[i + it].toInt() and 0xFF) }
        } else null
        return RespuestaNetBios(nombre, mac?.takeIf { MacUtils.esUtilizable(it) })
    }
}

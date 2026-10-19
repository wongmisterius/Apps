package com.wongmisterius.escanerred.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Resolución inversa por mDNS unicast: se envía una consulta PTR
 * "d.c.b.a.in-addr.arpa" directamente al puerto 5353 del equipo.
 * iPhone/Mac, Android con servicios, Chromecast, impresoras y Linux con Avahi
 * suelen responder con su nombre ".local".
 */
object Mdns {

    fun nombreInverso(ip: String, timeoutMs: Int = 700): String? = try {
        val consulta = construirConsulta(ip.split('.').reversed().joinToString(".") + ".in-addr.arpa")
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(consulta, consulta.size, InetAddress.getByName(ip), 5353))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            parsearPtr(buf, p.length)
        }
    } catch (_: Exception) {
        null
    }

    private fun construirConsulta(nombre: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (etiqueta in nombre.split('.')) {
            val bytes = etiqueta.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x0C, 0x00, 0x01)) // PTR, IN
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Lee un nombre DNS (con compresión). Devuelve el nombre y la posición siguiente. */
    private fun leerNombre(b: ByteArray, len: Int, inicio: Int): Pair<String, Int>? {
        val partes = mutableListOf<String>()
        var i = inicio
        var siguiente = -1
        var saltos = 0
        while (i < len) {
            val l = b[i].toInt() and 0xFF
            when {
                l == 0 -> {
                    if (siguiente < 0) siguiente = i + 1
                    return partes.joinToString(".") to siguiente
                }
                l and 0xC0 == 0xC0 -> {
                    if (i + 1 >= len || ++saltos > 20) return null
                    if (siguiente < 0) siguiente = i + 2
                    i = ((l and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF)
                }
                else -> {
                    if (i + 1 + l > len) return null
                    partes += String(b, i + 1, l, Charsets.UTF_8)
                    i += 1 + l
                }
            }
        }
        return null
    }

    private fun parsearPtr(b: ByteArray, len: Int): String? {
        if (len < 12) return null
        val preguntas = u16(b, 4)
        val respuestas = u16(b, 6) + u16(b, 8) + u16(b, 10)
        var i = 12
        repeat(preguntas) {
            i = (leerNombre(b, len, i) ?: return null).second + 4
        }
        repeat(respuestas) {
            i = (leerNombre(b, len, i) ?: return null).second
            if (i + 10 > len) return null
            val tipo = u16(b, i)
            val rdLen = u16(b, i + 8)
            i += 10
            if (tipo == 12) {
                return leerNombre(b, len, i)?.first?.removeSuffix(".")
            }
            i += rdLen
        }
        return null
    }
}

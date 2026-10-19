package com.wongmisterius.escanerred.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacUtilsTest {

    @Test
    fun normaliza_formatos() {
        assertEquals("AA:BB:CC:DD:EE:FF", MacUtils.normalizar("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", MacUtils.normalizar("aabb.ccdd.eeff"))
        assertNull(MacUtils.normalizar("AA:BB:CC"))
    }

    @Test
    fun detecta_mac_aleatoria() {
        // Segundo dígito 2, 6, A o E -> administrada localmente
        listOf("DA:A1:19:00:00:01", "02:11:22:33:44:55", "F6:00:00:00:00:00", "AE:12:34:56:78:9A").forEach {
            assertEquals(it, TipoMac.ALEATORIA, MacUtils.clasificar(it))
        }
        assertEquals(TipoMac.GLOBAL, MacUtils.clasificar("3C:22:FB:12:34:56"))
        assertEquals(TipoMac.MULTICAST, MacUtils.clasificar("01:00:5E:00:00:FB"))
        assertEquals(TipoMac.INVALIDA, MacUtils.clasificar("02:00:00:00:00:00"))
    }

    @Test
    fun placeholders_no_son_utilizables() {
        assertFalse(MacUtils.esUtilizable("02:00:00:00:00:00"))
        assertFalse(MacUtils.esUtilizable("00:00:00:00:00:00"))
        assertTrue(MacUtils.esUtilizable("3C:22:FB:12:34:56"))
    }

    @Test
    fun ipv4_rango() {
        assertEquals("192.168.1.0", Ipv4.aTexto(Ipv4.aEntero("192.168.1.77") and Ipv4.mascara(24)))
        assertEquals(254, Ipv4.mascara(24).inv() - 1)
    }

    @Test
    fun parsea_respuesta_nbstat() {
        val cab = ByteArray(12)
        val nombre = byteArrayOf(0x20) + ByteArray(32) { 'A'.code.toByte() } + byteArrayOf(0)
        val meta = byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, 0, 0x41)
        val entrada = "PC-SALA".padEnd(15).toByteArray() + byteArrayOf(0x00, 0x04, 0x00)
        val grupo = "WORKGROUP".padEnd(15).toByteArray() + byteArrayOf(0x00, 0x84.toByte(), 0x00)
        val mac = byteArrayOf(0x3C, 0x22, 0xFB.toByte(), 0x12, 0x34, 0x56)
        val pkt = cab + nombre + meta + byteArrayOf(2) + entrada + grupo + mac
        val r = NetBios.parsear(pkt, pkt.size)!!
        assertEquals("PC-SALA", r.nombre)
        assertEquals("3C:22:FB:12:34:56", r.mac)
    }
}

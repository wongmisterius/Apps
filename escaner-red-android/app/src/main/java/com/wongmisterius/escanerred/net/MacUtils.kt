package com.wongmisterius.escanerred.net

/** Tipo de dirección MAC según los bits del primer octeto (IEEE 802 / SLAP). */
enum class TipoMac(val descripcion: String) {
    GLOBAL("MAC de fábrica (asignada por la IEEE)"),
    ALEATORIA("MAC aleatoria / privada (administrada localmente)"),
    LOCAL_ELI("MAC local con identificador de empresa (CID)"),
    MULTICAST("MAC multicast (no corresponde a un dispositivo)"),
    INVALIDA("MAC no válida")
}

object MacUtils {

    /** MACs que Android/Linux devuelven cuando no hay dato real. */
    private val MACS_VACIAS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")

    private val SOLO_HEX = Regex("[^0-9A-Fa-f]")

    /** Normaliza a "AA:BB:CC:DD:EE:FF". Devuelve null si no tiene 12 dígitos hex. */
    fun normalizar(mac: String?): String? {
        if (mac == null) return null
        val hex = mac.replace(SOLO_HEX, "").uppercase()
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }

    /** True si la MAC es un dato real utilizable (no vacía ni placeholder). */
    fun esUtilizable(mac: String?): Boolean {
        val n = normalizar(mac) ?: return false
        return n !in MACS_VACIAS
    }

    fun primerOcteto(mac: String): Int = mac.substring(0, 2).toInt(16)

    /** Bit U/L (0x02): 1 = administrada localmente -> típicamente MAC aleatoria. */
    fun esAdministradaLocalmente(mac: String): Boolean = primerOcteto(mac) and 0x02 != 0

    /** Bit I/G (0x01): 1 = dirección de grupo/multicast. */
    fun esMulticast(mac: String): Boolean = primerOcteto(mac) and 0x01 != 0

    /**
     * Clasifica la MAC. Las MACs privadas de Android 10+, iOS 14+ y Windows 10+
     * se generan con el bit "localmente administrada" encendido, por lo que el
     * segundo dígito hex siempre es 2, 6, A o E (x2:, x6:, xA:, xE:).
     *
     * Cuadrante SLAP (bits 2-3 del primer octeto):
     *  - xA (ELI) puede pertenecer a una empresa registrada como CID en la IEEE.
     *  - x2 / x6 / xE: aleatoria o asignada por software.
     */
    fun clasificar(mac: String?, esCid: Boolean = false): TipoMac {
        val n = normalizar(mac) ?: return TipoMac.INVALIDA
        if (n in MACS_VACIAS) return TipoMac.INVALIDA
        if (esMulticast(n)) return TipoMac.MULTICAST
        if (!esAdministradaLocalmente(n)) return TipoMac.GLOBAL
        return if (esCid) TipoMac.LOCAL_ELI else TipoMac.ALEATORIA
    }

    /** Prefijo hex (sin separadores) de los primeros [digitos] dígitos. */
    fun prefijoHex(mac: String, digitos: Int): String = mac.replace(":", "").substring(0, digitos)
}

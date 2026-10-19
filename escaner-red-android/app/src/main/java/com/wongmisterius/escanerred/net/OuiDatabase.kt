package com.wongmisterius.escanerred.net

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class InfoFabricante(
    val fabricante: String?,
    val tipoMac: TipoMac
)

/**
 * Base de fabricantes de la IEEE (MA-L, MA-M, MA-S y CID) embebida en assets/oui.tsv.gz.
 * Se regenera con tools/generar_oui.py.
 */
class OuiDatabase private constructor(
    private val mal: Map<String, String>,  // 6 dígitos hex
    private val mam: Map<String, String>,  // 7 dígitos hex
    private val mas: Map<String, String>,  // 9 dígitos hex
    private val cid: Map<String, String>   // 6 dígitos hex
) {

    fun consultar(mac: String?): InfoFabricante {
        val n = MacUtils.normalizar(mac)
        if (n == null || !MacUtils.esUtilizable(n)) return InfoFabricante(null, TipoMac.INVALIDA)

        if (MacUtils.esAdministradaLocalmente(n)) {
            // Solo el cuadrante ELI (segundo dígito = A) puede llevar un CID
            val empresa = if (n[1] == 'A') cid[MacUtils.prefijoHex(n, 6)] else null
            return InfoFabricante(empresa, MacUtils.clasificar(n, esCid = empresa != null))
        }

        // Un bloque MA-L puede estar subdividido en MA-M / MA-S: buscar del más específico al general
        val org = mas[MacUtils.prefijoHex(n, 9)]
            ?: mam[MacUtils.prefijoHex(n, 7)]
            ?: mal[MacUtils.prefijoHex(n, 6)]
        return InfoFabricante(org, MacUtils.clasificar(n))
    }

    companion object {
        @Volatile
        private var instancia: OuiDatabase? = null

        fun get(context: Context): OuiDatabase =
            instancia ?: synchronized(this) {
                instancia ?: cargar(context.applicationContext).also { instancia = it }
            }

        private fun cargar(context: Context): OuiDatabase {
            val mal = HashMap<String, String>(48_000)
            val mam = HashMap<String, String>(8_000)
            val mas = HashMap<String, String>(8_000)
            val cid = HashMap<String, String>(300)
            context.assets.open("oui.tsv.gz").use { raw ->
                BufferedReader(InputStreamReader(GZIPInputStream(raw), Charsets.UTF_8)).forEachLine { linea ->
                    val p = linea.split('\t', limit = 3)
                    if (p.size == 3) {
                        when (p[0]) {
                            "L" -> mal[p[1]] = p[2]
                            "M" -> mam[p[1]] = p[2]
                            "S" -> mas[p[1]] = p[2]
                            "C" -> cid[p[1]] = p[2]
                        }
                    }
                }
            }
            return OuiDatabase(mal, mam, mas, cid)
        }
    }
}

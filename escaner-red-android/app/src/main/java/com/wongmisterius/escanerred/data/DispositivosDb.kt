package com.wongmisterius.escanerred.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class DispositivoRegistrado(
    val id: Long = 0,
    val nombre: String,
    val mac: String?,
    val fabricante: String?,
    val macAleatoria: Boolean,
    val ultimaIp: String?,
    val hostname: String?,
    val notas: String?,
    val creado: Long = System.currentTimeMillis(),
    val visto: Long? = null
)

class DispositivosDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "escaner_red.db", null, VERSION) {

    companion object {
        private const val VERSION = 1
        private const val T = "dispositivos"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                nombre        TEXT    NOT NULL,
                mac           TEXT    UNIQUE,
                fabricante    TEXT,
                mac_aleatoria INTEGER NOT NULL DEFAULT 0,
                ultima_ip     TEXT,
                hostname      TEXT,
                notas         TEXT,
                creado        INTEGER NOT NULL,
                visto         INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_${T}_ip ON $T(ultima_ip)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migraciones futuras: if (oldVersion < 2) db.execSQL("ALTER TABLE ...")
    }

    fun todos(): List<DispositivoRegistrado> =
        readableDatabase.query(T, null, null, null, null, null, "nombre COLLATE NOCASE").use { c ->
            generateSequence { if (c.moveToNext()) leer(c) else null }.toList()
        }

    /** Inserta o actualiza. Devuelve el id. */
    fun guardar(d: DispositivoRegistrado): Long {
        val v = ContentValues().apply {
            put("nombre", d.nombre)
            put("mac", d.mac)
            put("fabricante", d.fabricante)
            put("mac_aleatoria", if (d.macAleatoria) 1 else 0)
            put("ultima_ip", d.ultimaIp)
            put("hostname", d.hostname)
            put("notas", d.notas)
            put("creado", d.creado)
            put("visto", d.visto)
        }
        val db = writableDatabase
        return if (d.id == 0L) {
            db.insertOrThrow(T, null, v)
        } else {
            db.update(T, v, "id = ?", arrayOf(d.id.toString()))
            d.id
        }
    }

    fun eliminar(id: Long) {
        writableDatabase.delete(T, "id = ?", arrayOf(id.toString()))
    }

    fun existeMac(mac: String, exceptoId: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM $T WHERE mac = ? AND id <> ?", arrayOf(mac, exceptoId.toString()))
            .use { it.moveToFirst() }

    /** Actualiza IP / hostname / fecha de visto tras un escaneo, en una sola transacción. */
    fun marcarVistos(vistos: List<Triple<Long, String, String?>>, fecha: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, ip, host) in vistos) {
                val v = ContentValues().apply {
                    put("ultima_ip", ip)
                    put("visto", fecha)
                    if (host != null) put("hostname", host)
                }
                db.update(T, v, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun leer(c: Cursor) = DispositivoRegistrado(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        nombre = c.getString(c.getColumnIndexOrThrow("nombre")),
        mac = c.texto("mac"),
        fabricante = c.texto("fabricante"),
        macAleatoria = c.getInt(c.getColumnIndexOrThrow("mac_aleatoria")) == 1,
        ultimaIp = c.texto("ultima_ip"),
        hostname = c.texto("hostname"),
        notas = c.texto("notas"),
        creado = c.getLong(c.getColumnIndexOrThrow("creado")),
        visto = c.getColumnIndexOrThrow("visto").let { if (c.isNull(it)) null else c.getLong(it) }
    )

    private fun Cursor.texto(col: String): String? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
}

package com.sysdvr.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Pantalla de conexión: IP manual (persistida) + consolas descubiertas por UDP. */
class MainActivity : Activity() {
    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var scanStatus: TextView
    private lateinit var consoleList: LinearLayout
    private var scanner: ConsoleScanner? = null
    private val found = LinkedHashMap<String, Protocol.DeviceInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ip_input)
        connectButton = findViewById(R.id.connect_button)
        scanStatus = findViewById(R.id.scan_status)
        consoleList = findViewById(R.id.console_list)

        ipInput.setText(Prefs.lastIp(this))
        ipInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || enter) {
                hideKeyboard()
                connectButton.requestFocus()
                true
            } else false
        }
        connectButton.setOnClickListener { connect(ipInput.text.toString().trim()) }
        connectButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        found.clear()
        consoleList.removeAllViews()
        scanStatus.setText(R.string.scanning)
        scanner = ConsoleScanner(this,
            onFound = { info -> runOnUiThread { addConsole(info) } },
            onError = { msg -> runOnUiThread { scanStatus.text = msg } }
        ).also { it.start() }
    }

    override fun onPause() {
        scanner?.stop()
        scanner = null
        super.onPause()
    }

    private fun addConsole(info: Protocol.DeviceInfo) {
        if (found.put(info.ip, info) != null) return
        scanStatus.text = getString(R.string.consoles_found, found.size)
        val button = layoutInflater.inflate(R.layout.item_console, consoleList, false) as Button
        val serial = if (info.serial.isBlank()) "" else " · ${info.serial}"
        button.text = getString(R.string.console_item, info.ip, info.version, info.protocol) + serial
        button.setOnClickListener {
            ipInput.setText(info.ip)
            connect(info.ip)
        }
        consoleList.addView(button)
        // Primera consola encontrada: dejarla enfocada si el usuario no se movió
        if (found.size == 1 && connectButton.isFocused) button.requestFocus()
    }

    private fun connect(ip: String) {
        if (!isValidIp(ip)) {
            ipInput.error = getString(R.string.invalid_ip)
            ipInput.requestFocus()
            return
        }
        Prefs.saveIp(this, ip)
        startActivity(Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_IP, ip))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(ipInput.windowToken, 0)
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}

object Prefs {
    private const val FILE = "sysdvr"
    private const val KEY_IP = "last_ip"
    private const val DEFAULT_IP = "192.168.1.101"

    fun lastIp(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP

    fun saveIp(ctx: Context, ip: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_IP, ip).apply()
}

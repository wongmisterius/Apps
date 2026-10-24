package com.sysdvr.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.os.Build

/** Reproducción a pantalla completa. Overlay con estado y "Reconectar" navegable con D-pad. */
class StreamActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var reconnectButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var host: String

    private var video: VideoDecoder? = null
    private var audio: AudioPlayer? = null
    private var videoChannel: StreamChannel? = null
    private var audioChannel: StreamChannel? = null

    private val statuses = mutableMapOf<Protocol.Stream, Pair<StreamChannel.Status, String>>()
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_stream)
        goImmersive()

        host = intent.getStringExtra(EXTRA_IP) ?: Prefs.lastIp(this)
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status_text)
        reconnectButton = findViewById(R.id.reconnect_button)

        reconnectButton.setOnClickListener {
            statusText.setText(R.string.reconnecting)
            videoChannel?.reconnect()
            audioChannel?.reconnect()
        }
        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }

        surfaceView.holder.addCallback(this)
        showOverlay(autoHide = false)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startSession(holder)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) = stopSession()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopSession()
        super.onDestroy()
    }

    private fun startSession(holder: SurfaceHolder) {
        stopSession()
        val v = VideoDecoder(holder.surface)
        try {
            v.start()
        } catch (e: Exception) {
            statusText.text = getString(R.string.decoder_error, e.message)
            return
        }
        val a = AudioPlayer().also { it.start() }
        video = v
        audio = a
        videoChannel = StreamChannel(host, Protocol.Stream.VIDEO, v, ::onStatus).also { it.start() }
        audioChannel = StreamChannel(host, Protocol.Stream.AUDIO, a, ::onStatus).also { it.start() }
    }

    private fun stopSession() {
        videoChannel?.stop(); videoChannel = null
        audioChannel?.stop(); audioChannel = null
        video?.release(); video = null
        audio?.release(); audio = null
    }

    private fun onStatus(stream: Protocol.Stream, status: StreamChannel.Status, msg: String) {
        ui.post {
            statuses[stream] = status to msg
            val lines = Protocol.Stream.values().mapNotNull { statuses[it]?.second }
            val decoder = video?.decoderName?.let { "\nDecoder: $it" } ?: ""
            statusText.text = lines.joinToString("\n") + decoder

            val allStreaming = Protocol.Stream.values().all { statuses[it]?.first == StreamChannel.Status.STREAMING }
            if (allStreaming) showOverlay(autoHide = true) else showOverlay(autoHide = false)
        }
    }

    private fun showOverlay(autoHide: Boolean) {
        ui.removeCallbacks(hideOverlay)
        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            reconnectButton.requestFocus()
        }
        if (autoHide) ui.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showOverlay(autoHide = true)
                    return true
                }
            }
        } else if (keyCode != KeyEvent.KEYCODE_BACK) {
            // Mantener visible mientras el usuario navega
            ui.removeCallbacks(hideOverlay)
            ui.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    companion object {
        const val EXTRA_IP = "ip"
        private const val OVERLAY_TIMEOUT_MS = 4000L
    }
}

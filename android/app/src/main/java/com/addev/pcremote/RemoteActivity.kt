package com.addev.pcremote

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class RemoteActivity : Activity(), RemoteClient.Listener {

    private lateinit var mousePanel: View
    private lateinit var screenPanel: View
    private lateinit var keyboardPanel: View
    private lateinit var screenView: ScreenView
    private lateinit var keyInput: KeyCaptureEditText
    private lateinit var tabMouse: Button
    private lateinit var tabScreen: Button
    private lateinit var btnKeyboard: Button
    private lateinit var btnQuality: Button
    private lateinit var btnMonitor: Button

    private var screenMode = false
    private var monitor = 0

    /** Calidad de imagen: (nombre, ancho máx, calidad JPEG, fps) */
    private val qualities = listOf(
        Triple("Baja", 960, 45) to 15,
        Triple("Media", 1280, 60) to 15,
        Triple("Alta", 1920, 75) to 12,
    )
    private var qualityIdx = 1

    /** Modificadores "pegajosos": se aplican a la siguiente tecla y se sueltan. */
    private val activeMods = linkedSetOf<String>()
    private val modButtons = HashMap<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RemoteClient.isConnected) { finish(); return }
        setContentView(R.layout.activity_remote)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mousePanel = findViewById(R.id.mousePanel)
        screenPanel = findViewById(R.id.screenPanel)
        keyboardPanel = findViewById(R.id.keyboardPanel)
        screenView = findViewById(R.id.screenView)
        keyInput = findViewById(R.id.keyInput)
        tabMouse = findViewById(R.id.tabMouse)
        tabScreen = findViewById(R.id.tabScreen)
        btnKeyboard = findViewById(R.id.btnKeyboard)
        btnQuality = findViewById(R.id.btnQuality)
        btnMonitor = findViewById(R.id.btnMonitor)
        findViewById<TextView>(R.id.title).text = RemoteClient.serverName

        val prefs = getSharedPreferences("pcremote", MODE_PRIVATE)
        qualityIdx = prefs.getInt("quality", 1).coerceIn(0, qualities.size - 1)

        tabMouse.setOnClickListener { setScreenMode(false) }
        tabScreen.setOnClickListener { setScreenMode(true) }
        btnKeyboard.setOnClickListener { toggleKeyboard() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { finish() }

        holdButton(findViewById(R.id.btnLeft), "left")
        holdButton(findViewById(R.id.btnMiddle), "middle")
        holdButton(findViewById(R.id.btnRight), "right")

        btnQuality.setOnClickListener {
            qualityIdx = (qualityIdx + 1) % qualities.size
            prefs.edit().putInt("quality", qualityIdx).apply()
            updateScreenButtons()
            requestScreen(true)
        }
        btnMonitor.setOnClickListener {
            monitor = (monitor + 1) % RemoteClient.monitors.coerceAtLeast(1)
            updateScreenButtons()
            screenView.clear()
            requestScreen(true)
        }
        val btnFit = findViewById<Button>(R.id.btnFit)
        btnFit.setOnClickListener { screenView.toggleFit() }
        screenView.onZoomStateChanged = { zoomed -> btnFit.text = if (zoomed) "Ver todo" else "Ajustar alto" }
        btnMonitor.visibility = if (RemoteClient.monitors > 1) View.VISIBLE else View.GONE

        keyInput.onCharWithModifiers = { s -> sendWithModifiers(s) }
        buildKeysRow()
        updateScreenButtons()
        setScreenMode(false)
    }

    /** Botón que se mantiene pulsado = botón del ratón mantenido (permite arrastrar con el touchpad). */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdButton(b: Button, button: String) {
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    RemoteClient.send("btn", "b" to button, "down" to true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    RemoteClient.send("btn", "b" to button, "down" to false)
                }
            }
            true
        }
    }

    private fun buildKeysRow() {
        val row = findViewById<LinearLayout>(R.id.keysRow)
        fun add(label: String, onClick: (Button) -> Unit): Button {
            val b = Button(this, null, 0, R.style.Btn).apply {
                text = label
                textSize = 14f
                setPadding(32, 0, 32, 0)
                setOnClickListener { onClick(this) }
            }
            row.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = 8 })
            return b
        }
        fun key(label: String, k: String) = add(label) { sendKey(k) }
        fun mod(label: String, m: String) {
            modButtons[m] = add(label) { b ->
                if (!activeMods.remove(m)) activeMods.add(m)
                b.isSelected = m in activeMods
            }
        }

        mod("Ctrl", "ctrl"); mod("Alt", "alt"); mod("Shift", "shift")
        key("Win", "win"); key("Esc", "esc"); key("Tab", "tab")
        key("←", "left"); key("↑", "up"); key("↓", "down"); key("→", "right")
        key("⌫", "backspace"); key("Supr", "delete"); key("↵", "enter")
        key("Inicio", "home"); key("Fin", "end"); key("RePág", "pageup"); key("AvPág", "pagedown")
        for (i in 1..12) key("F$i", "f$i")
        key("Vol −", "volumedown"); key("Vol +", "volumeup"); key("Mute", "volumemute")
        key("⏯", "playpause")
    }

    /** Tecla especial: aplica los modificadores activos (Ctrl, Alt…) y los suelta. */
    private fun sendKey(k: String) {
        val mods = takeMods()
        RemoteClient.send("key", "k" to k, "mods" to org.json.JSONArray(mods))
    }

    /** Si hay modificadores activos, el carácter escrito se envía como combinación (Ctrl+C…). */
    private fun sendWithModifiers(s: String): Boolean {
        if (activeMods.isEmpty()) return false
        val mods = takeMods()
        val ch = s.lastOrNull()?.lowercaseChar()?.toString() ?: return true
        RemoteClient.send("key", "k" to (if (ch == " ") "space" else ch), "mods" to org.json.JSONArray(mods))
        return true
    }

    private fun takeMods(): List<String> {
        val mods = activeMods.toList()
        activeMods.clear()
        modButtons.values.forEach { it.isSelected = false }
        return mods
    }

    private fun setScreenMode(on: Boolean) {
        screenMode = on
        mousePanel.visibility = if (on) View.GONE else View.VISIBLE
        screenPanel.visibility = if (on) View.VISIBLE else View.GONE
        tabMouse.isSelected = !on
        tabScreen.isSelected = on
        requestScreen(on)
    }

    private fun updateScreenButtons() {
        btnQuality.text = qualities[qualityIdx].first.first
        btnMonitor.text = "Monitor ${monitor + 1}"
    }

    private fun requestScreen(on: Boolean) {
        val (q, fps) = qualities[qualityIdx]
        RemoteClient.send("screen", "on" to on, "mon" to monitor, "w" to q.second, "q" to q.third, "fps" to fps)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (keyboardPanel.visibility == View.VISIBLE) {
            imm.hideSoftInputFromWindow(keyInput.windowToken, 0)
            keyboardPanel.visibility = View.GONE
            btnKeyboard.isSelected = false
        } else {
            keyboardPanel.visibility = View.VISIBLE
            btnKeyboard.isSelected = true
            keyInput.reset()
            keyInput.requestFocus()
            keyInput.post { imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    override fun onResume() {
        super.onResume()
        RemoteClient.listener = this
        if (!RemoteClient.isConnected) { finish(); return }
        if (screenMode) requestScreen(true)
    }

    override fun onPause() {
        super.onPause()
        // no gastar red ni batería con la app en segundo plano
        if (screenMode) requestScreen(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (RemoteClient.listener === this) RemoteClient.listener = null
        if (isFinishing) RemoteClient.disconnect()
    }

    override fun onFrame(bitmap: Bitmap) {
        if (screenMode) screenView.setFrame(bitmap)
    }

    override fun onDisconnected(reason: String) {
        Toast.makeText(this, "Desconectado: $reason", Toast.LENGTH_LONG).show()
        finish()
    }
}

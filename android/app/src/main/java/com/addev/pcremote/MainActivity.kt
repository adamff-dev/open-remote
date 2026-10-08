package com.addev.pcremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var pin: EditText
    private lateinit var status: TextView
    private lateinit var scanStatus: TextView
    private lateinit var serverList: LinearLayout
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button

    private val prefs by lazy { getSharedPreferences("pcremote", MODE_PRIVATE) }
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        pin = findViewById(R.id.pin)
        status = findViewById(R.id.status)
        scanStatus = findViewById(R.id.scanStatus)
        serverList = findViewById(R.id.serverList)
        btnConnect = findViewById(R.id.btnConnect)
        btnScan = findViewById(R.id.btnScan)

        host.setText(prefs.getString("host", ""))
        port.setText(prefs.getInt("port", 47000).toString())
        pin.setText(prefs.getString("pin", ""))

        btnConnect.setOnClickListener { connect() }
        btnScan.setOnClickListener { scan() }
        scan()
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        btnScan.isEnabled = false
        scanStatus.text = "Buscando…"
        Thread {
            val found = runCatching { Discovery.discover(this) }.getOrDefault(emptyList())
            runOnUiThread {
                scanning = false
                btnScan.isEnabled = true
                serverList.removeAllViews()
                scanStatus.text = if (found.isEmpty())
                    "No se encontró ningún servidor. ¿Está arrancado y en la misma Wi-Fi?"
                else ""
                for (f in found) {
                    val b = Button(this, null, 0, R.style.Btn).apply {
                        text = "${f.name}\n${f.host}:${f.port}"
                        textAlignment = Button.TEXT_ALIGNMENT_VIEW_START
                        setPadding(40, 24, 40, 24)
                        setOnClickListener {
                            host.setText(f.host)
                            port.setText(f.port.toString())
                            if (pin.text.isNullOrBlank()) {
                                pin.requestFocus()
                                status.text = "Introduce el PIN que muestra el servidor"
                            } else connect()
                        }
                    }
                    serverList.addView(b, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 })
                }
            }
        }.start()
    }

    private fun connect() {
        val h = host.text.toString().trim()
        val p = port.text.toString().toIntOrNull() ?: 47000
        val code = pin.text.toString().trim()
        if (h.isEmpty()) {
            status.text = "Escribe la IP del PC o elige un servidor de la lista"
            return
        }
        btnConnect.isEnabled = false
        status.text = "Conectando a $h:$p…"
        Thread {
            val error = runCatching { RemoteClient.connect(h, p, code) }.exceptionOrNull()
            runOnUiThread {
                btnConnect.isEnabled = true
                if (error == null) {
                    prefs.edit().putString("host", h).putInt("port", p).putString("pin", code).apply()
                    status.text = ""
                    startActivity(Intent(this, RemoteActivity::class.java))
                } else {
                    status.text = "No se pudo conectar: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }
}

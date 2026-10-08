package com.addev.pcremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Conexión TCP con el servidor.
 *   Cliente -> servidor: una línea JSON por mensaje.
 *   Servidor -> cliente: [tipo 1 byte][longitud 4 bytes BE][payload]  (1 = JSON, 2 = JPEG)
 */
object RemoteClient {

    interface Listener {
        fun onFrame(bitmap: Bitmap)
        fun onDisconnected(reason: String)
    }

    private const val MSG_JSON = 1
    private const val MSG_JPEG = 2

    @Volatile var listener: Listener? = null
    var serverName: String = ""
        private set
    var monitors: Int = 1
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var conn: Connection? = null

    val isConnected: Boolean get() = conn != null

    /** Bloqueante: llamar desde un hilo de fondo. Lanza IOException si falla. */
    fun connect(host: String, port: Int, pin: String) {
        disconnect()
        val c = Connection(host, port, pin)
        val hello = c.open()
        serverName = hello.optString("name", host)
        monitors = hello.optInt("monitors", 1)
        conn = c
        c.start()
    }

    fun disconnect() {
        conn?.close(null)
        conn = null
    }

    fun send(obj: JSONObject) {
        conn?.queue?.offer(obj.toString())
    }

    fun send(type: String, vararg pairs: Pair<String, Any?>) {
        val o = JSONObject().put("t", type)
        for ((k, v) in pairs) o.put(k, v)
        send(o)
    }

    private class Connection(val host: String, val port: Int, val pin: String) {
        val queue = LinkedBlockingQueue<String>()
        private val socket = Socket()
        private lateinit var input: DataInputStream
        private lateinit var output: BufferedOutputStream
        @Volatile private var closed = false

        fun open(): JSONObject {
            try {
                socket.connect(InetSocketAddress(host, port), 4000)
                socket.tcpNoDelay = true
                socket.soTimeout = 6000
                input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                output = BufferedOutputStream(socket.getOutputStream())
                writeLine(JSONObject().put("t", "hello").put("pin", pin).toString())
                output.flush()
                val (type, payload) = readMessage()
                if (type != MSG_JSON) throw IOException("Respuesta inesperada del servidor")
                val hello = JSONObject(String(payload, Charsets.UTF_8))
                if (!hello.optBoolean("ok")) throw IOException(hello.optString("error", "Conexión rechazada"))
                socket.soTimeout = 0
                return hello
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw if (e is IOException) e else IOException(e.message, e)
            }
        }

        fun start() {
            Thread(::writerLoop, "remote-writer").start()
            Thread(::readerLoop, "remote-reader").start()
        }

        private fun writeLine(s: String) {
            output.write(s.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
        }

        private fun readMessage(): Pair<Int, ByteArray> {
            val type = input.readUnsignedByte()
            val len = input.readInt()
            if (len < 0 || len > 32 * 1024 * 1024) throw IOException("Mensaje inválido")
            val buf = ByteArray(len)
            input.readFully(buf)
            return type to buf
        }

        private fun writerLoop() {
            try {
                while (!closed) {
                    val first = queue.take()
                    if (closed) break
                    writeLine(first)
                    // agrupa lo que haya pendiente en un solo flush
                    while (true) writeLine(queue.poll() ?: break)
                    output.flush()
                }
            } catch (e: Exception) {
                close("Conexión perdida")
            }
        }

        private fun readerLoop() {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            try {
                while (!closed) {
                    val (type, payload) = readMessage()
                    if (type == MSG_JPEG) {
                        val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size, opts)
                        queue.offer("{\"t\":\"ack\"}")
                        if (bmp != null) RemoteClient.main.post { if (!closed) RemoteClient.listener?.onFrame(bmp) }
                    }
                }
            } catch (e: Exception) {
                close("Conexión perdida")
            }
        }

        fun close(reason: String?) {
            if (closed) return
            closed = true
            queue.offer("")  // despierta al writer
            runCatching { socket.close() }
            if (reason != null) {
                RemoteClient.main.post {
                    if (RemoteClient.conn === this) {
                        RemoteClient.conn = null
                        RemoteClient.listener?.onDisconnected(reason)
                    }
                }
            }
        }
    }
}

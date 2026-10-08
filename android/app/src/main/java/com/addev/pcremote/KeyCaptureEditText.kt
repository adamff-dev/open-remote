package com.addev.pcremote

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * Campo de texto que reenvía al PC, en vivo, lo que se escribe con el teclado de Android.
 *
 * Se compara el texto anterior con el nuevo (prefijo común) y se envían los borrados como
 * Backspace y lo añadido como texto Unicode. Así funcionan autocorrector, predicción,
 * escritura por gestos y dictado por voz, que reescriben palabras enteras.
 */
class KeyCaptureEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : EditText(context, attrs) {

    /** Si devuelve true, el texto escrito se ha consumido (p. ej. Ctrl+letra) y no se envía como texto. */
    var onCharWithModifiers: ((String) -> Boolean)? = null

    private var prev = ""
    private var suppress = false

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!suppress) onTextEdited(s.toString())
            }
        })
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                // teclado sin texto delante del cursor: el IME manda la tecla DEL directamente
                KeyEvent.KEYCODE_DEL -> if (text.isEmpty()) { sendKey("backspace"); true } else false
                KeyEvent.KEYCODE_FORWARD_DEL -> { sendKey("delete"); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { sendKey("enter"); reset(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { sendKey("left"); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { sendKey("right"); true }
                KeyEvent.KEYCODE_DPAD_UP -> { sendKey("up"); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { sendKey("down"); true }
                KeyEvent.KEYCODE_TAB -> { sendKey("tab"); true }
                KeyEvent.KEYCODE_ESCAPE -> { sendKey("esc"); true }
                else -> false
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        // multilínea para que Enter llegue como salto de línea / tecla, nunca como "acción"
        outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
        return object : InputConnectionWrapper(base, true) {
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                // borrar con el campo vacío: no hay cambio de texto que detectar, lo enviamos aquí
                if (text.isEmpty() && before > 0) {
                    sendKey("backspace", before)
                    return true
                }
                return super.deleteSurroundingText(before, after)
            }
        }
    }

    private fun onTextEdited(now: String) {
        // Enter dentro del texto (teclados que insertan '\n')
        val nl = now.indexOf('\n')
        if (nl >= 0) {
            diffAndSend(prev, now.substring(0, nl))
            sendKey("enter")
            reset()
            return
        }
        if (diffAndSend(prev, now)) {
            reset()  // combinación con modificador: no dejamos ese carácter en el campo
            return
        }
        prev = now
        // evita que el campo crezca sin límite: se vacía tras un espacio cuando es largo
        if (now.length > 120 && now.endsWith(" ")) reset()
    }

    /** Devuelve true si lo añadido se consumió como combinación de teclas. */
    private fun diffAndSend(old: String, now: String): Boolean {
        var p = 0
        val max = minOf(old.length, now.length)
        while (p < max && old[p] == now[p]) p++
        if (p > 0 && Character.isHighSurrogate(old[p - 1])) p--  // no partir emojis
        val removed = old.codePointCount(p, old.length)
        val added = now.substring(p)
        if (removed > 0) sendKey("backspace", removed)
        if (added.isNotEmpty()) {
            if (onCharWithModifiers?.invoke(added) == true) return true
            RemoteClient.send("text", "s" to added)
        }
        return false
    }

    fun reset() {
        suppress = true
        setText("")
        suppress = false
        prev = ""
    }

    private fun sendKey(k: String, n: Int = 1) {
        RemoteClient.send("key", "k" to k, "n" to n)
    }
}

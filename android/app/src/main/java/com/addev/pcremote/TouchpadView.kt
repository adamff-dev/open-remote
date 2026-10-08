package com.addev.pcremote

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Touchpad:
 *  - 1 dedo deslizar: mover ratón (con aceleración)
 *  - 1 dedo tocar: clic izquierdo
 *  - 2 dedos tocar: clic derecho · 3 dedos tocar: clic central
 *  - 2 dedos deslizar: scroll vertical/horizontal
 *  - mantener pulsado sin mover y luego deslizar: arrastrar (botón izquierdo mantenido)
 */
class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var sensitivity = 1.4f

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeout = 250L
    private val longPressTimeout = 400L

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var dragging = false
    private var scrollLastX = 0f
    private var scrollLastY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var scrollTravel = 0f

    private val longPress = Runnable {
        if (!moved && maxPointers == 1) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            RemoteClient.send("btn", "b" to "left", "down" to true)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                lastMoveTime = e.eventTime
                maxPointers = 1
                moved = false
                dragging = false
                postDelayed(longPress, longPressTimeout)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                scrollLastX = centroidX(e); scrollLastY = centroidY(e)
                scrollAccX = 0f; scrollAccY = 0f; scrollTravel = 0f
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // al levantar un dedo, reinicia referencias para evitar saltos
                val upIdx = e.actionIndex
                val keep = if (upIdx == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
                scrollLastX = centroidX(e, upIdx); scrollLastY = centroidY(e, upIdx)
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    handleScroll(e)
                } else if (maxPointers == 1 || dragging) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) {
                        moved = true
                        if (!dragging) removeCallbacks(longPress)
                    }
                    if (moved) sendMove(dx, dy, e.eventTime)
                    lastX = e.x; lastY = e.y
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val quick = e.eventTime - downTime < tapTimeout
                when {
                    dragging -> RemoteClient.send("btn", "b" to "left", "down" to false)
                    !moved && quick && maxPointers == 1 -> click("left")
                    !moved && quick && maxPointers == 2 -> click("right")
                    !moved && quick && maxPointers >= 3 -> click("middle")
                }
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) RemoteClient.send("btn", "b" to "left", "down" to false)
                dragging = false
            }
        }
        return true
    }

    private fun click(b: String) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        RemoteClient.send("click", "b" to b)
    }

    private fun sendMove(dxPx: Float, dyPx: Float, time: Long) {
        val dt = (time - lastMoveTime).coerceAtLeast(1L)
        lastMoveTime = time
        val dx = dxPx / density
        val dy = dyPx / density
        val speed = hypot(dx, dy) / dt  // dp por ms
        val factor = sensitivity * (1f + min(speed, 3f) * 0.9f)
        if (dx != 0f || dy != 0f) {
            RemoteClient.send("move", "dx" to (dx * factor).toDouble(), "dy" to (dy * factor).toDouble())
        }
    }

    private fun handleScroll(e: MotionEvent) {
        val cx = centroidX(e)
        val cy = centroidY(e)
        val dx = (cx - scrollLastX) / density
        val dy = (cy - scrollLastY) / density
        scrollLastX = cx; scrollLastY = cy
        scrollTravel += abs(dx) + abs(dy)
        if (!moved && scrollTravel * density < touchSlop) return
        moved = true
        // scroll natural (como en el móvil): dedos hacia arriba = bajar en la página
        scrollAccY += dy * 4f
        scrollAccX -= dx * 4f
        val sy = scrollAccY.toInt()
        val sx = scrollAccX.toInt()
        if (abs(sy) >= 8 || abs(sx) >= 8) {
            RemoteClient.send("scroll", "dy" to sy, "dx" to sx)
            scrollAccY -= sy
            scrollAccX -= sx
        }
    }

    private fun centroidX(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getX(i); n++ }
        return if (n == 0) 0f else s / n
    }

    private fun centroidY(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getY(i); n++ }
        return if (n == 0) 0f else s / n
    }
}

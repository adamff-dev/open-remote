package com.addev.pcremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Muestra la pantalla del PC.
 *  - tocar: clic izquierdo en ese punto (dos toques rápidos = doble clic)
 *  - mantener pulsado: clic derecho
 *  - pellizcar: zoom · arrastrar: desplazarse por la imagen ampliada
 */
class ScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A8F98")
        textSize = 15f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private var zoom = 1f
    private var panX = 0f   // desplazamiento en px de vista
    private var panY = 0f
    private val drawMatrix = Matrix()
    private val inverse = Matrix()

    fun setFrame(bmp: Bitmap) {
        val old = bitmap
        val sizeChanged = old == null || old.width != bmp.width || old.height != bmp.height
        bitmap = bmp
        if (sizeChanged && !fitHeight) {
            zoom = 1f; panX = 0f; panY = 0f
            notifyZoom()
        }
        invalidate()
    }

    /** Se llama cuando cambia entre "vista completa" y "ampliada" (para actualizar el botón). */
    var onZoomStateChanged: ((zoomed: Boolean) -> Unit)? = null

    /** true: la imagen ocupa todo el alto disponible (se recalcula al rotar o cambiar calidad). */
    private var fitHeight = false

    val isZoomed: Boolean get() = fitHeight || zoom > 1.01f

    private fun notifyZoom() = onZoomStateChanged?.invoke(isZoomed)

    fun clear() {
        bitmap = null
        invalidate()
    }

    /** Alterna entre ver la pantalla completa y ajustar la imagen al alto de la vista. */
    fun toggleFit() {
        fitHeight = !isZoomed
        zoom = 1f; panX = 0f; panY = 0f
        invalidate()
        notifyZoom()
    }

    /** Zoom necesario para que la imagen ocupe todo el alto de la vista. */
    private fun heightZoom(b: Bitmap) = maxOf(1f, height.toFloat() / b.height / baseScale(b))

    private fun baseScale(b: Bitmap) = minOf(width.toFloat() / b.width, height.toFloat() / b.height)

    private fun updateMatrix(b: Bitmap) {
        if (fitHeight) zoom = heightZoom(b)
        val s = baseScale(b) * zoom
        val w = b.width * s
        val h = b.height * s
        // límites: la imagen no puede salirse más allá de sus bordes
        val maxPanX = maxOf(0f, (w - width) / 2f)
        val maxPanY = maxOf(0f, (h - height) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        drawMatrix.reset()
        drawMatrix.postScale(s, s)
        drawMatrix.postTranslate((width - w) / 2f + panX, (height - h) / 2f + panY)
        drawMatrix.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null) {
            canvas.drawText("Esperando imagen del PC…", width / 2f, height / 2f, hintPaint)
            return
        }
        updateMatrix(b)
        canvas.drawBitmap(b, drawMatrix, paint)
    }

    /** Convierte un punto de la vista a coordenadas normalizadas (0..1) de la pantalla del PC. */
    private fun toNormalized(x: Float, y: Float): Pair<Float, Float>? {
        val b = bitmap ?: return null
        updateMatrix(b)
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        val nx = pts[0] / b.width
        val ny = pts[1] / b.height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return nx to ny
    }

    private fun tap(x: Float, y: Float, button: String) {
        val p = toNormalized(x, y) ?: return
        RemoteClient.send("tap", "x" to p.first.toDouble(), "y" to p.second.toDouble(), "b" to button)
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val b = bitmap ?: return true
                val wasZoomed = isZoomed
                fitHeight = false
                val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 6f)
                val f = newZoom / zoom
                // zoom centrado en el punto entre los dedos
                val cx = d.focusX - width / 2f
                val cy = d.focusY - height / 2f
                panX = cx - (cx - panX) * f
                panY = cy - (cy - panY) * f
                zoom = newZoom
                updateMatrix(b)
                invalidate()
                if (wasZoomed != isZoomed) notifyZoom()
                return true
            }
        })

    // OnGestureListener "puro" (sin doble toque) para que cada toque llegue al instante
    private val gestureDetector = GestureDetector(context, object : GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent) = true
        override fun onShowPress(e: MotionEvent) {}
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y, "left")
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx
            panY -= dy
            invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (scaleDetector.isInProgress) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            tap(e.x, e.y, "right")
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float) = false
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount == 1 || !scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}

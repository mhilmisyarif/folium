package dev.mangareader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * Page image for the paged readers, with Kotatsu's scale modes and pinch / double-tap zoom.
 * Whenever the image is larger than the view (zoomed, or a fit-width page taller than the screen) it pans; the
 * gesture goes back to the pager once the image edge is reached in that direction, so pages still turn.
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {

    /** Kotatsu "Mode skala" (zoom_mode). */
    enum class ScaleMode { FIT_CENTER, FIT_HEIGHT, FIT_WIDTH, KEEP_START }

    var scaleMode = ScaleMode.FIT_CENTER
        set(value) { field = value; fitBase(); apply() }

    private val base = Matrix()   // scale mode
    private val zoom = Matrix()   // user zoom/pan on top
    private val drawMatrix = Matrix()
    private val bounds = RectF()
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale * d.scaleFactor).coerceIn(MIN, MAX)
            val factor = target / scale
            scale = target
            zoom.postScale(factor, factor, d.focusX, d.focusY)
            apply()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) reset() else {
                scale = DOUBLE_TAP
                zoom.postScale(DOUBLE_TAP, DOUBLE_TAP, e.x, e.y)
                apply()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!overflows()) return false
            zoom.postTranslate(-dx, -dy)
            apply()
            return true
        }
    })

    fun reset() {
        scale = 1f
        zoom.reset()
        apply()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        zoom.reset(); scale = 1f
        fitBase(); apply()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitBase(); apply()
    }

    private fun fitBase() {
        val d = drawable ?: return
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (width == 0 || height == 0 || iw <= 0 || ih <= 0) return
        val sx = width / iw
        val sy = height / ih
        val s = when (scaleMode) {
            ScaleMode.FIT_CENTER -> minOf(sx, sy)
            ScaleMode.FIT_HEIGHT -> sy
            ScaleMode.FIT_WIDTH -> sx
            ScaleMode.KEEP_START -> maxOf(sx, sy) // fills the screen, starting at the top-start corner
        }
        val cw = iw * s
        val ch = ih * s
        // Centre what fits; start at the top/left edge of what overflows.
        val tx = if (cw <= width || scaleMode == ScaleMode.FIT_CENTER) (width - cw) / 2 else 0f
        val ty = if (ch <= height || scaleMode == ScaleMode.FIT_CENTER) (height - ch) / 2 else 0f
        base.setScale(s, s)
        base.postTranslate(tx, ty)
    }

    private fun mapped(): RectF? {
        val d = drawable ?: return null
        drawMatrix.set(base); drawMatrix.postConcat(zoom)
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        return bounds
    }

    private fun overflows(): Boolean = mapped()?.let { it.width() > width + 0.5f || it.height() > height + 0.5f } == true

    /** Applies base × zoom and keeps the image covering the view (no empty gaps while it overflows). */
    private fun apply() {
        mapped()?.let { b ->
            var dx = 0f
            var dy = 0f
            if (b.width() <= width) dx = width / 2f - b.centerX()
            else if (b.left > 0) dx = -b.left else if (b.right < width) dx = width - b.right
            if (b.height() <= height) dy = height / 2f - b.centerY()
            else if (b.top > 0) dy = -b.top else if (b.bottom < height) dy = height - b.bottom
            if (scale <= 1.01f && !overflows()) { dx = 0f; dy = 0f } // at rest the scale mode decides the position
            zoom.postTranslate(dx, dy)
        }
        drawMatrix.set(base); drawMatrix.postConcat(zoom)
        imageMatrix = drawMatrix
    }

    /** True when the image can still pan horizontally in [direction] (<0 = left, >0 = right). */
    override fun canScrollHorizontally(direction: Int): Boolean {
        val b = mapped() ?: return false
        if (b.width() <= width + 0.5f) return false
        return if (direction < 0) b.left < -0.5f else b.right > width + 0.5f
    }

    override fun canScrollVertically(direction: Int): Boolean {
        val b = mapped() ?: return false
        if (b.height() <= height + 0.5f) return false
        return if (direction < 0) b.top < -0.5f else b.bottom > height + 0.5f
    }

    @SuppressLint("ClickableViewAccessibility") // zoom is a visual aid; page turning stays on the reader's tap zones
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        // Keep pinches and pans for the image; hand the gesture to the pager at the image edge so pages still turn.
        val keep = when {
            event.pointerCount > 1 || scaleDetector.isInProgress -> true
            !overflows() -> false
            event.actionMasked == MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) < 1f && abs(dy) < 1f) true
                else if (abs(dx) >= abs(dy)) canScrollHorizontally(if (dx > 0) -1 else 1)
                else canScrollVertically(if (dy > 0) -1 else 1)
            }
            else -> true
        }
        lastX = event.x
        lastY = event.y
        parent?.requestDisallowInterceptTouchEvent(keep)
        return true
    }

    private companion object {
        const val MIN = 1f
        const val MAX = 4f
        const val DOUBLE_TAP = 2.5f
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * One comic page: fits the screen, pinch or double-tap to zoom, drag to look around. When the page is
 * not zoomed (or is dragged to its edge) swipes pass through so the pager can turn the page.
 */
class ZoomImageView(context: Context) : ImageView(context) {
    /** Called for a single tap with the tap's x position and the view's width. */
    var onTap: ((Float, Int) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    private var zoom = 1f
    private val maxZoom = 4f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) {
                zoomBy(1f / zoom, e.x, e.y)
            } else {
                zoomBy(2.5f, e.x, e.y)
            }
            parent?.requestDisallowInterceptTouchEvent(zoom > 1.05f)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke(e.x, width)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom <= 1.05f) return false
            val atLeft = translationX() >= -0.5f
            val atRight = translationX() + scaledWidth() <= width + 0.5f
            val wantsPrevious = distanceX < 0
            val blocked = (wantsPrevious && atLeft) || (!wantsPrevious && atRight)
            parent?.requestDisallowInterceptTouchEvent(!blocked)
            drawMatrix.postTranslate(-distanceX, -distanceY)
            clampAndApply()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(zoom > 1.05f)
        }
        if (event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun imageWidth(): Float = drawable?.intrinsicWidth?.toFloat() ?: 0f
    private fun imageHeight(): Float = drawable?.intrinsicHeight?.toFloat() ?: 0f

    private fun translationX(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MTRANS_X]
    }

    private fun scaledWidth(): Float {
        drawMatrix.getValues(values)
        return imageWidth() * values[Matrix.MSCALE_X]
    }

    private fun fit() {
        val iw = imageWidth()
        val ih = imageHeight()
        if (iw <= 0f || ih <= 0f || width == 0 || height == 0) return
        fitScale = minOf(width / iw, height / ih)
        zoom = 1f
        drawMatrix.reset()
        drawMatrix.postScale(fitScale, fitScale)
        drawMatrix.postTranslate((width - iw * fitScale) / 2f, (height - ih * fitScale) / 2f)
        imageMatrix = drawMatrix
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (drawable == null) return
        val target = (zoom * factor).coerceIn(1f, maxZoom)
        val applied = target / zoom
        if (applied == 1f) return
        drawMatrix.postScale(applied, applied, focusX, focusY)
        zoom = target
        clampAndApply()
    }

    private fun clampAndApply() {
        val iw = imageWidth()
        val ih = imageHeight()
        if (iw <= 0f || ih <= 0f) return
        val rect = RectF(0f, 0f, iw, ih)
        drawMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        dx = when {
            rect.width() <= width -> (width - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        dy = when {
            rect.height() <= height -> (height - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }
}

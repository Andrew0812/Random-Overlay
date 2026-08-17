package com.example.randomoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class AreaSelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val selectionPaint = Paint().apply {
        color = Color.argb(70, 0, 128, 255) // Blue
        style = Paint.Style.FILL
    }
    private val primedPaint = Paint().apply {
        color = Color.argb(70, 34, 139, 34)  // Green
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(200, 0, 100, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
    }
    private val handleBorderPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var selectionRect = RectF()
    private val handleRadius = 30f
    private val minSize = 100f
    private var isPrimedForConfirmation = false

    private enum class DragMode {
        NONE, MOVE, RESIZE_TOP_LEFT, RESIZE_TOP_RIGHT, RESIZE_BOTTOM_LEFT, RESIZE_BOTTOM_RIGHT,
        RESIZE_LEFT, RESIZE_TOP, RESIZE_RIGHT, RESIZE_BOTTOM
    }
    private var currentDragMode = DragMode.NONE
    private var lastTouchPoint = PointF()

    var onAreaConfirmedListener: (() -> Unit)? = null

    // The GestureDetector handles both scrolling (dragging) and single taps.
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Determine which part of the rectangle is being touched.
            currentDragMode = getDragModeForTouchPoint(e.x, e.y)
            // If we touched a valid part, we want to listen for subsequent events.
            return currentDragMode != DragMode.NONE
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (currentDragMode != DragMode.NONE) {
                // Any drag/resize action resets the confirmation priming.
                if (isPrimedForConfirmation) {
                    isPrimedForConfirmation = false
                }
                // We use -distanceX and -distanceY because onScroll gives the delta
                // of the "content" moving, which is the inverse of the finger's movement.
                updateRect(-distanceX, -distanceY)
                invalidate()
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // A tap is only valid if it's inside the rectangle.
            if (selectionRect.contains(e.x, e.y)) {
                performClick() // Delegate to performClick to handle the logic.
                return true
            } else {
                // If the user taps outside, reset the priming state.
                if (isPrimedForConfirmation) {
                    isPrimedForConfirmation = false
                    invalidate()
                }
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let the gesture detector do all the work.
        // It will call onDown, onScroll, or onSingleTapUp as appropriate.
        gestureDetector.onTouchEvent(event)

        // On ACTION_UP, reset the drag mode.
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            currentDragMode = DragMode.NONE
        }

        return true // Consume the touch event.
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (isPrimedForConfirmation) {
            // This is the second tap (confirmation)
            onAreaConfirmedListener?.invoke()
        } else {
            // This is the first tap (priming)
            isPrimedForConfirmation = true
            invalidate()
        }
        return true
    }

    fun setRect(rect: RectF) {
        selectionRect.set(rect)
        invalidate()
    }

    fun getRect(): RectF {
        return selectionRect
    }

    private fun getDragModeForTouchPoint(x: Float, y: Float): DragMode {
        val touchArea = handleRadius * 1.5f
        if (isNearHandle(x, y, selectionRect.left, selectionRect.top, touchArea)) return DragMode.RESIZE_TOP_LEFT
        if (isNearHandle(x, y, selectionRect.right, selectionRect.top, touchArea)) return DragMode.RESIZE_TOP_RIGHT
        if (isNearHandle(x, y, selectionRect.left, selectionRect.bottom, touchArea)) return DragMode.RESIZE_BOTTOM_LEFT
        if (isNearHandle(x, y, selectionRect.right, selectionRect.bottom, touchArea)) return DragMode.RESIZE_BOTTOM_RIGHT
        if (abs(x - selectionRect.left) < touchArea && y > selectionRect.top && y < selectionRect.bottom) return DragMode.RESIZE_LEFT
        if (abs(x - selectionRect.right) < touchArea && y > selectionRect.top && y < selectionRect.bottom) return DragMode.RESIZE_RIGHT
        if (abs(y - selectionRect.top) < touchArea && x > selectionRect.left && x < selectionRect.right) return DragMode.RESIZE_TOP
        if (abs(y - selectionRect.bottom) < touchArea && x > selectionRect.left && x < selectionRect.right) return DragMode.RESIZE_BOTTOM
        if (selectionRect.contains(x, y)) return DragMode.MOVE
        return DragMode.NONE
    }

    private fun isNearHandle(x1: Float, y1: Float, x2: Float, y2: Float, tolerance: Float): Boolean {
        return abs(x1 - x2) < tolerance && abs(y1 - y2) < tolerance
    }

    private fun updateRect(dx: Float, dy: Float) {
        when (currentDragMode) {
            DragMode.MOVE -> selectionRect.offset(dx, dy)
            DragMode.RESIZE_TOP_LEFT -> {
                selectionRect.left = (selectionRect.left + dx).coerceAtMost(selectionRect.right - minSize)
                selectionRect.top = (selectionRect.top + dy).coerceAtMost(selectionRect.bottom - minSize)
            }
            DragMode.RESIZE_TOP_RIGHT -> {
                selectionRect.right = (selectionRect.right + dx).coerceAtLeast(selectionRect.left + minSize)
                selectionRect.top = (selectionRect.top + dy).coerceAtMost(selectionRect.bottom - minSize)
            }
            DragMode.RESIZE_BOTTOM_LEFT -> {
                selectionRect.left = (selectionRect.left + dx).coerceAtMost(selectionRect.right - minSize)
                selectionRect.bottom = (selectionRect.bottom + dy).coerceAtLeast(selectionRect.top + minSize)
            }
            DragMode.RESIZE_BOTTOM_RIGHT -> {
                selectionRect.right = (selectionRect.right + dx).coerceAtLeast(selectionRect.left + minSize)
                selectionRect.bottom = (selectionRect.bottom + dy).coerceAtLeast(selectionRect.top + minSize)
            }
            DragMode.RESIZE_LEFT -> selectionRect.left = (selectionRect.left + dx).coerceAtMost(selectionRect.right - minSize)
            DragMode.RESIZE_RIGHT -> selectionRect.right = (selectionRect.right + dx).coerceAtLeast(selectionRect.left + minSize)
            DragMode.RESIZE_TOP -> selectionRect.top = (selectionRect.top + dy).coerceAtMost(selectionRect.bottom - minSize)
            DragMode.RESIZE_BOTTOM -> selectionRect.bottom = (selectionRect.bottom + dy).coerceAtLeast(selectionRect.top + minSize)
            DragMode.NONE -> {}
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Set the paint color based on the priming state
        val currentPaint = if (isPrimedForConfirmation) primedPaint else selectionPaint
        canvas.drawRect(selectionRect, currentPaint)
        canvas.drawRect(selectionRect, borderPaint)
        drawHandle(canvas, selectionRect.left, selectionRect.top)
        drawHandle(canvas, selectionRect.right, selectionRect.top)
        drawHandle(canvas, selectionRect.left, selectionRect.bottom)
        drawHandle(canvas, selectionRect.right, selectionRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
        canvas.drawCircle(x, y, handleRadius, handleBorderPaint)
    }
}
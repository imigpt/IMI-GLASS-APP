package com.sdk.glassessdksample.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Orbital loading animation for the BLE gate screen.
 * Draws 5 dots orbiting a center point at different radii and phases.
 */
class DotsOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7F2E")
        style = Paint.Style.FILL
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var angle = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    private data class Dot(val radiusFraction: Float, val phase: Float, val size: Float, val alpha: Int)

    private val dots = listOf(
        Dot(0.30f, 0f,    10f, 255),
        Dot(0.45f, 72f,   8f,  200),
        Dot(0.55f, 144f,  7f,  170),
        Dot(0.38f, 216f,  9f,  220),
        Dot(0.50f, 288f,  6f,  150)
    )

    init {
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.85f

        dots.forEach { dot ->
            val a = Math.toRadians((angle + dot.phase).toDouble())
            val r = maxR * dot.radiusFraction
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            paint.alpha = dot.alpha
            canvas.drawCircle(x, y, dot.size, paint)
        }

        // Center glow dot
        paint.alpha = 180
        canvas.drawCircle(cx, cy, 6f, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isRunning) animator.start()
    }
}

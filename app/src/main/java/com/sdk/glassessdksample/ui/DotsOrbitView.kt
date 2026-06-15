package com.sdk.glassessdksample.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Scattered-dots constellation animation with IMI center label for the BLE gate screen.
 * Dots drift slowly outward and twinkle to match the reference design.
 */
class DotsOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#444444")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        style = Paint.Style.FILL
    }

    // Each dot: (angleDeg, radiusFraction, sizeDp, baseAlpha, blueHue)
    // blueHue=true → blue dot, false → white/grey dot
    private data class Dot(
        val angleDeg: Float,
        val radiusFraction: Float,
        val sizeDp: Float,
        val baseAlpha: Float,
        val isBlue: Boolean,
        var phase: Float = 0f   // twinkle phase offset (0..2π)
    )

    private val dots = listOf(
        // Blue dots — prominent
        Dot(15f,  0.55f, 7f,  0.90f, true),
        Dot(55f,  0.80f, 9f,  0.95f, true),
        Dot(90f,  0.65f, 6f,  0.80f, true),
        Dot(130f, 0.88f, 10f, 1.00f, true),
        Dot(165f, 0.58f, 7f,  0.85f, true),
        Dot(200f, 0.75f, 9f,  0.90f, true),
        Dot(240f, 0.62f, 6f,  0.80f, true),
        Dot(275f, 0.85f, 8f,  0.95f, true),
        Dot(310f, 0.55f, 7f,  0.85f, true),
        Dot(340f, 0.78f, 9f,  0.90f, true),
        // Lighter blue / smaller
        Dot(35f,  0.70f, 5f,  0.70f, true),
        Dot(110f, 0.72f, 5f,  0.65f, true),
        Dot(185f, 0.68f, 4f,  0.60f, true),
        Dot(255f, 0.73f, 5f,  0.70f, true),
        Dot(320f, 0.67f, 4f,  0.65f, true),
        // White/grey dots — small accent
        Dot(25f,  0.42f, 4f,  0.50f, false),
        Dot(75f,  0.38f, 3f,  0.45f, false),
        Dot(145f, 0.45f, 4f,  0.55f, false),
        Dot(215f, 0.40f, 3f,  0.45f, false),
        Dot(290f, 0.43f, 4f,  0.50f, false),
        Dot(355f, 0.39f, 3f,  0.40f, false),
    ).also { list ->
        list.forEachIndexed { i, dot -> dot.phase = (i * 0.6f) % (2f * Math.PI.toFloat()) }
    }

    private var twinkleAngle = 0f
    private val animator = ValueAnimator.ofFloat(0f, (2f * Math.PI.toFloat())).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            twinkleAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val density get() = resources.displayMetrics.density

    init { animator.start() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.88f

        // Draw scattered dots
        dots.forEach { dot ->
            val rad = Math.toRadians(dot.angleDeg.toDouble())
            val r = maxR * dot.radiusFraction
            val x = cx + (r * cos(rad)).toFloat()
            val y = cy + (r * sin(rad)).toFloat()

            // Twinkle: alpha oscillates gently
            val twinkle = 0.75f + 0.25f * sin((twinkleAngle + dot.phase).toDouble()).toFloat()
            val alpha = (dot.baseAlpha * twinkle * 255).toInt().coerceIn(0, 255)

            dotPaint.alpha = alpha
            dotPaint.color = if (dot.isBlue) Color.parseColor("#5BA4FF") else Color.parseColor("#CCCCCC")

            val radius = dot.sizeDp * density / 2f
            canvas.drawCircle(x, y, radius, dotPaint)
        }

        // Center circle (dark grey pill)
        val circleRadius = 44f * density
        canvas.drawCircle(cx, cy, circleRadius, centerCirclePaint)

        // "IMI" label
        textPaint.textSize = 22f * density
        canvas.drawText("IMI", cx, cy + 8f * density, textPaint)

        // Underline beneath IMI
        val lineW = 22f * density
        val lineY = cy + 18f * density
        canvas.drawRoundRect(
            cx - lineW / 2f, lineY, cx + lineW / 2f, lineY + 2f * density,
            1f * density, 1f * density, underlinePaint
        )
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

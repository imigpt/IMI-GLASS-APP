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
 * Dots orbit slowly around the center (parallax swirl — inner dots travel faster than
 * outer ones), drift gently in and out, and twinkle. Coloured to match the app's
 * orange theme.
 */
class DotsOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3D2C1E")   // dark warm circle behind IMI
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7F2E")
        style = Paint.Style.FILL
    }

    // App theme colours
    private val colorOrange = Color.parseColor("#FF7F2E")       // primary dots
    private val colorOrangeLight = Color.parseColor("#FFB07A")  // softer dots
    private val colorAccent = Color.parseColor("#FFD9B8")       // small warm accents

    // Each dot orbits around the center. tone: 0 = orange, 1 = light orange, 2 = warm accent
    private data class Dot(
        val angleDeg: Float,
        val radiusFraction: Float,
        val sizeDp: Float,
        val baseAlpha: Float,
        val tone: Int,
        var phase: Float = 0f,   // twinkle phase offset (0..2π)
        var speed: Float = 0.5f  // orbit speed multiplier (parallax)
    )

    private val dots = listOf(
        // Primary orange dots — prominent
        Dot(15f,  0.55f, 7f,  0.90f, 0),
        Dot(55f,  0.80f, 9f,  0.95f, 0),
        Dot(90f,  0.65f, 6f,  0.80f, 0),
        Dot(130f, 0.88f, 10f, 1.00f, 0),
        Dot(165f, 0.58f, 7f,  0.85f, 0),
        Dot(200f, 0.75f, 9f,  0.90f, 0),
        Dot(240f, 0.62f, 6f,  0.80f, 0),
        Dot(275f, 0.85f, 8f,  0.95f, 0),
        Dot(310f, 0.55f, 7f,  0.85f, 0),
        Dot(340f, 0.78f, 9f,  0.90f, 0),
        Dot(5f,   0.92f, 8f,  0.92f, 0),
        Dot(70f,  0.50f, 6f,  0.82f, 0),
        Dot(150f, 0.82f, 9f,  0.95f, 0),
        Dot(225f, 0.90f, 8f,  0.90f, 0),
        Dot(300f, 0.70f, 7f,  0.85f, 0),
        // Softer / smaller light-orange
        Dot(35f,  0.70f, 5f,  0.70f, 1),
        Dot(110f, 0.72f, 5f,  0.65f, 1),
        Dot(185f, 0.68f, 4f,  0.60f, 1),
        Dot(255f, 0.73f, 5f,  0.70f, 1),
        Dot(320f, 0.67f, 4f,  0.65f, 1),
        Dot(50f,  0.95f, 5f,  0.68f, 1),
        Dot(125f, 0.60f, 5f,  0.66f, 1),
        Dot(170f, 0.92f, 4f,  0.62f, 1),
        Dot(280f, 0.60f, 5f,  0.70f, 1),
        Dot(345f, 0.62f, 4f,  0.64f, 1),
        // Warm accent dots — small
        Dot(25f,  0.42f, 4f,  0.50f, 2),
        Dot(75f,  0.38f, 3f,  0.45f, 2),
        Dot(145f, 0.45f, 4f,  0.55f, 2),
        Dot(215f, 0.40f, 3f,  0.45f, 2),
        Dot(290f, 0.43f, 4f,  0.50f, 2),
        Dot(355f, 0.39f, 3f,  0.40f, 2),
        Dot(60f,  0.48f, 3f,  0.42f, 2),
        Dot(120f, 0.35f, 3f,  0.48f, 2),
        Dot(190f, 0.50f, 4f,  0.52f, 2),
        Dot(250f, 0.36f, 3f,  0.44f, 2),
        Dot(330f, 0.47f, 4f,  0.50f, 2),
    ).also { list ->
        list.forEachIndexed { i, dot ->
            dot.phase = (i * 0.6f) % (2f * Math.PI.toFloat())
            // Inner dots travel faster than outer ones → parallax swirl
            dot.speed = 1.15f - dot.radiusFraction
        }
    }

    private var twinkleAngle = 0f   // 0..2π, drives twinkle
    private val twinkle = ValueAnimator.ofFloat(0f, (2f * Math.PI.toFloat())).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            twinkleAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private var orbitAngle = 0f   // 0..2π, drives the swirl
    private val orbit = ValueAnimator.ofFloat(0f, (2f * Math.PI.toFloat())).apply {
        duration = 10000   // one full revolution
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            orbitAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val density get() = resources.displayMetrics.density

    init {
        twinkle.start()
        orbit.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.88f

        // Draw orbiting dots
        dots.forEach { dot ->
            // Orbit: each dot's angle advances by orbitAngle scaled by its speed
            val angleRad = Math.toRadians(dot.angleDeg.toDouble()) + orbitAngle * dot.speed
            // Gentle radial breathing so dots drift in and out
            val breathe = 1f + 0.06f * sin((orbitAngle * 2f + dot.phase).toDouble()).toFloat()
            val r = maxR * dot.radiusFraction * breathe
            val x = cx + (r * cos(angleRad)).toFloat()
            val y = cy + (r * sin(angleRad)).toFloat()

            // Twinkle: alpha oscillates gently
            val tw = 0.75f + 0.25f * sin((twinkleAngle + dot.phase).toDouble()).toFloat()
            val alpha = (dot.baseAlpha * tw * 255).toInt().coerceIn(0, 255)

            dotPaint.alpha = alpha
            dotPaint.color = when (dot.tone) {
                0 -> colorOrange
                1 -> colorOrangeLight
                else -> colorAccent
            }

            val radius = dot.sizeDp * density / 2f
            canvas.drawCircle(x, y, radius, dotPaint)
        }

        // Center circle (dark warm pill)
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
        twinkle.cancel()
        orbit.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!twinkle.isRunning) twinkle.start()
        if (!orbit.isRunning) orbit.start()
    }
}

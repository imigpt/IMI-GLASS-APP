package com.sdk.glassessdksample.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * Animated "listening" waveform — a row of rounded bars that ripple in a sine wave,
 * tinted with the app's warm orange→amber gradient. Feed live mic amplitude via
 * [setAmplitude] (0f..1f) to make the wave react to the user's voice.
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 24
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f
    private var amplitude = 0.25f          // current (smoothed) level
    private var targetAmplitude = 0.25f    // latest requested level

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            // Ease the amplitude toward the latest target for a smooth response.
            amplitude += (targetAmplitude - amplitude) * 0.2f
            invalidate()
        }
    }

    /** level: 0f (silent) .. 1f (loud). Call from the speech recognizer's RMS callback. */
    fun setAmplitude(level: Float) {
        targetAmplitude = level.coerceIn(0.08f, 1f)
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
        amplitude = 0.25f
        targetAmplitude = 0.25f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xFFFF6900.toInt(), 0xFFFF7F2E.toInt(), 0xFFFF8B46.toInt()),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w / barCount
        val barWidth = gap * 0.45f
        val radius = barWidth / 2f
        val centerY = h / 2f
        val maxBar = h * 0.7f
        val minBar = h * 0.16f

        for (i in 0 until barCount) {
            val cx = gap * i + gap / 2f
            // Travelling sine wave, modulated by live amplitude.
            val wave = abs(sin(phase + i * 0.55f))
            val barHeight = (minBar + (maxBar - minBar) * wave * amplitude).coerceAtLeast(minBar)
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            canvas.drawRoundRect(cx - barWidth / 2f, top, cx + barWidth / 2f, bottom, radius, radius, barPaint)
        }
    }
}

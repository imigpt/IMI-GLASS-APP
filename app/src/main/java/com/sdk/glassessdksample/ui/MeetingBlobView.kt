package com.sdk.glassessdksample.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Organic blob glow driven purely by microphone amplitude.
 *
 * Silence  (amp=0) → completely invisible, frozen.
 * Speaking (amp>0) → layers grow, spin, and brighten proportionally to loudness.
 *
 * Call setAmplitude(0f..1f) every ~100 ms from the recording loop.
 */
class MeetingBlobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val matrix = Matrix()

    private val assetNames = listOf(
        "Ellipse 137.png",
        "Ellipse 136.png",
        "Ellipse 136 (1).png",
        "Ellipse 135.png",
        "Ellipse 135 (1).png"
    )
    private val bitmaps = mutableListOf<Bitmap>()

    private data class Layer(
        val rotSpeed: Float,    // deg/s at full amplitude
        val pulseSpeed: Float,
        val pulseAmount: Float,
        val baseScale: Float,   // scale fraction at full amplitude
        val phase: Float,
        val maxAlpha: Float     // alpha at full amplitude
    )

    private val layers = listOf(
        Layer(rotSpeed = 30f,  pulseSpeed = 1.1f, pulseAmount = 0.07f, baseScale = 1.00f, phase = 0.0f, maxAlpha = 0.85f),
        Layer(rotSpeed = -40f, pulseSpeed = 1.4f, pulseAmount = 0.08f, baseScale = 0.92f, phase = 1.2f, maxAlpha = 0.80f),
        Layer(rotSpeed = 50f,  pulseSpeed = 0.9f, pulseAmount = 0.06f, baseScale = 0.85f, phase = 2.4f, maxAlpha = 0.75f),
        Layer(rotSpeed = -35f, pulseSpeed = 1.6f, pulseAmount = 0.09f, baseScale = 0.78f, phase = 3.6f, maxAlpha = 0.70f),
        Layer(rotSpeed = 60f,  pulseSpeed = 1.25f, pulseAmount = 0.06f, baseScale = 0.70f, phase = 4.8f, maxAlpha = 0.65f)
    )

    // smoothedAmp: 0 = total silence, 1 = loudest
    private var smoothedAmp = 0f

    /**
     * Feed normalised amplitude here every ~100 ms.
     * Fast attack so the blob reacts instantly to speech onset.
     * Slow decay so it fades gracefully when speech stops.
     */
    fun setAmplitude(normalised: Float) {
        val clamped = normalised.coerceIn(0f, 1f)
        smoothedAmp = if (clamped > smoothedAmp) {
            smoothedAmp + (clamped - smoothedAmp) * 0.5f   // fast attack
        } else {
            smoothedAmp + (clamped - smoothedAmp) * 0.06f  // slow decay
        }
    }

    private var elapsedMs = 0L
    private var startTime = 0L

    // Keep animator running always so onDraw fires and smoothedAmp decays to 0 naturally.
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (startTime == 0L) startTime = System.currentTimeMillis()
            elapsedMs = System.currentTimeMillis() - startTime
            invalidate()
        }
    }

    init { loadBitmaps() }

    private fun loadBitmaps() {
        if (bitmaps.isNotEmpty()) return
        val am = context.assets
        for (name in assetNames) {
            try {
                am.open("meeting min animaton/$name").use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bitmaps.add(it) }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bitmaps.isEmpty()) return

        val amp = smoothedAmp
        val cx = width / 2f
        val cy = height / 2f
        val t  = elapsedMs / 1000f

        // Always show a small resting blob (30%), grows to max 65% at loud speech.
        val targetFraction = (0.30f + amp * 0.35f).coerceIn(0.30f, 0.65f)
        val target = minOf(width, height) * targetFraction

        // Rotation: very slow at rest (5 deg/s), faster with speech (up to 45 deg/s)
        val speedMul = 0.15f + amp * 0.85f

        layers.forEachIndexed { i, layer ->
            val bmp = bitmaps[i % bitmaps.size]

            // Pulse around the amp-driven base scale
            val pulse = layer.baseScale +
                    layer.pulseAmount * sin((t * layer.pulseSpeed + layer.phase).toDouble()).toFloat()
            val scale = (target / maxOf(bmp.width, bmp.height)) * pulse

            // Rotation accumulates with time, scaled by amplitude
            val rotation = (t * layer.rotSpeed * speedMul) % 360f

            // Resting alpha ~25%, rises to maxAlpha at full volume
            val breath = 0.85f + 0.15f * sin((t * layer.pulseSpeed * 0.8f + layer.phase).toDouble()).toFloat()
            val alphaLevel = (0.25f + amp * 0.75f).coerceIn(0.25f, 1.0f)
            paint.alpha = (layer.maxAlpha * alphaLevel * breath * 255).toInt().coerceIn(0, 255)

            matrix.reset()
            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
            matrix.postScale(scale, scale)
            matrix.postRotate(rotation)
            matrix.postTranslate(cx, cy)

            canvas.drawBitmap(bmp, matrix, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = 0L
        if (!animator.isRunning) animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}

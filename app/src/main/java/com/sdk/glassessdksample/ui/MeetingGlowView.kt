package com.sdk.glassessdksample.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Static soft orange glow that sits BEHIND the [MeetingBlobView].
 *
 * Purely decorative and fixed — it does NOT animate or react to microphone
 * amplitude. It draws a smooth radial halo around the timer so the ring looks
 * the same at all times.
 */
class MeetingGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val orange = Color.parseColor("#FF8C3A")
    private val orangeDeep = Color.parseColor("#E5702A")

    private var shader: RadialGradient? = null

    /** Kept for API compatibility with the recording loop. Intentionally a no-op. */
    fun setAmplitude(@Suppress("UNUSED_PARAMETER") normalised: Float) {
        // Static glow — ignores amplitude.
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f

        // Fixed brightness — never changes. Orange stays solid up to the edge of
        // the inner circle, then fades out quickly so it only hugs the timer.
        val centerAlpha = (0.55f * 255).toInt()
        val midAlpha = (0.45f * 255).toInt()

        shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                colorWithAlpha(orange, centerAlpha),
                colorWithAlpha(orange, midAlpha),
                colorWithAlpha(orangeDeep, (0.20f * 255).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.72f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = shader ?: return
        paint.shader = s
        canvas.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint)
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}

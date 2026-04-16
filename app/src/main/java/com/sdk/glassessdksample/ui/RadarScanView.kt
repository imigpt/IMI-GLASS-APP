package com.sdk.glassessdksample.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.sdk.glassessdksample.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class RadarScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Blip(
        val device: SmartWatch,
        val x: Float,
        val y: Float,
        val radius: Float,
        val distanceLabel: String,
        val labelYOffset: Float
    )

    private data class Particle(
        val angle: Float,
        val radiusFactor: Float,
        val size: Float,
        val phase: Float
    )

    private val colorBackgroundPrimary = ContextCompat.getColor(context, R.color.background_primary)
    private val colorBackgroundSecondary = ContextCompat.getColor(context, R.color.background_secondary)
    private val colorCardBackground = ContextCompat.getColor(context, R.color.card_background)
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorAccentWarm = ContextCompat.getColor(context, R.color.accent_warm_gray)
    private val colorAccentSilver = ContextCompat.getColor(context, R.color.accent_silver)
    private val colorAccentGlow = ContextCompat.getColor(context, R.color.accent_glow)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = withAlpha(colorAccentSilver, 0.22f)
    }

    private val glowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = withAlpha(colorAccentGlow, 0.82f)
    }

    private val centerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorCardBackground
    }

    private val centerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = withAlpha(colorAccentSilver, 0.75f)
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorAccentSilver
    }

    private val blipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = colorAccentGlow
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextPrimary
        textSize = sp(13f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorAccentSilver
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextPrimary
        textSize = sp(16f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val centerImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val deviceImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private val radarSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radarSweepSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepMatrix = Matrix()
    private val secondarySweepMatrix = Matrix()

    private val centerClipPath = Path()
    private val blipClipPath = Path()

    private val blips = mutableListOf<Blip>()
    private val particles = mutableListOf<Particle>()

    private var sweepAngle = 0f
    private var pulsePhase = 0f
    private var isScanning = false

    private val centerGlassesBitmap: Bitmap? = loadCenterGlassesBitmap()
    private val deviceNodeBitmap: Bitmap? = loadDeviceNodeBitmap()

    var onDeviceClick: ((SmartWatch) -> Unit)? = null

    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setScanning(scanning: Boolean) {
        if (isScanning == scanning) return
        isScanning = scanning
        if (scanning) {
            if (!sweepAnimator.isStarted) sweepAnimator.start()
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            sweepAnimator.cancel()
            pulseAnimator.cancel()
            sweepAngle = 0f
            pulsePhase = 0f
            invalidate()
        }
    }

    fun updateDevices(devices: List<SmartWatch>) {
        blips.clear()
        if (width == 0 || height == 0) {
            post { updateDevices(devices) }
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val radarRadius = min(width, height) * 0.42f
        val innerSafeRadius = radarRadius * 0.28f

        devices.take(10).forEachIndexed { index, device ->
            val strength = ((device.rssi + 95f) / 60f).coerceIn(0f, 1f)
            val radiusFactor = 1f - (strength * 0.72f)
            val ringRadius = innerSafeRadius + (radarRadius - innerSafeRadius) * radiusFactor
            val angle = stableAngle(device.deviceAddress)

            val x = cx + cos(angle) * ringRadius
            val y = cy + sin(angle) * ringRadius
            val dotRadius = dp(8f) + (strength * dp(4f))
            val labelOffset = when (index % 4) {
                0 -> -dp(4f)
                1 -> dp(5f)
                2 -> -dp(9f)
                else -> dp(9f)
            }

            blips.add(
                Blip(
                    device = device,
                    x = x,
                    y = y,
                    radius = dotRadius,
                    distanceLabel = "${estimateDistanceMeters(device.rssi)} m",
                    labelYOffset = labelOffset
                )
            )
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        particles.clear()
        repeat(24) {
            particles.add(
                Particle(
                    angle = Random.nextFloat() * (2f * PI.toFloat()),
                    radiusFactor = 0.20f + Random.nextFloat() * 0.78f,
                    size = dp(1f) + Random.nextFloat() * dp(1.8f),
                    phase = Random.nextFloat() * (2f * PI.toFloat())
                )
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnimator.cancel()
        pulseAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radarRadius = min(width, height) * 0.42f

        drawRadarGlow(canvas, cx, cy, radarRadius)
        drawAmbientParticles(canvas, cx, cy, radarRadius)
        drawRings(canvas, cx, cy, radarRadius)
        drawSweep(canvas, cx, cy, radarRadius)
        drawCenter(canvas, cx, cy, radarRadius * 0.23f)
        drawBlips(canvas)
    }

    private fun drawRadarGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    withAlpha(colorBackgroundSecondary, 0.96f),
                    withAlpha(colorCardBackground, 0.92f),
                    withAlpha(colorBackgroundPrimary, 1f)
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, glow)
    }

    private fun drawAmbientParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (particles.isEmpty()) return

        particles.forEach { particle ->
            val x = cx + cos(particle.angle) * (radius * particle.radiusFactor)
            val y = cy + sin(particle.angle) * (radius * particle.radiusFactor)
            val wave = 0.5f + 0.5f * kotlin.math.sin((pulsePhase * PI * 2f + particle.phase).toFloat())
            val alphaBase = 0.08f + 0.22f * wave
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(colorAccentGlow, alphaBase)
            }
            canvas.drawCircle(x, y, particle.size, dotPaint)
        }
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for (index in 1..4) {
            val ringRadius = radius * (index / 4f)
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }
        canvas.drawCircle(cx, cy, radius * 0.75f, glowRingPaint)
    }

    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (!isScanning) return

        radarSweepPaint.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(colorAccentWarm, 0.10f),
                withAlpha(colorAccentGlow, 0.42f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.74f, 0.86f, 1f)
        )

        sweepMatrix.reset()
        sweepMatrix.postRotate(sweepAngle, cx, cy)
        radarSweepPaint.shader?.setLocalMatrix(sweepMatrix)

        radarSweepSecondaryPaint.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(colorAccentSilver, 0.05f),
                withAlpha(colorAccentGlow, 0.18f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 0.66f, 1f)
        )

        secondarySweepMatrix.reset()
        secondarySweepMatrix.postRotate(-sweepAngle * 0.62f, cx, cy)
        radarSweepSecondaryPaint.shader?.setLocalMatrix(secondarySweepMatrix)

        canvas.drawCircle(cx, cy, radius, radarSweepPaint)
        canvas.drawCircle(cx, cy, radius * 0.84f, radarSweepSecondaryPaint)
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, centerFillPaint)
        canvas.drawCircle(cx, cy, radius, centerStrokePaint)

        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = withAlpha(colorAccentGlow, 0.45f)
        }
        canvas.drawCircle(cx, cy, radius + dp(6f) * pulsePhase, pulsePaint)

        val image = centerGlassesBitmap
        if (image != null) {
            val inset = dp(3f)
            val imageRadius = (radius - inset).coerceAtLeast(dp(10f))
            val dest = RectF(cx - imageRadius, cy - imageRadius, cx + imageRadius, cy + imageRadius)
            val saveCount = canvas.save()
            centerClipPath.reset()
            centerClipPath.addCircle(cx, cy, imageRadius, Path.Direction.CW)
            canvas.clipPath(centerClipPath)
            canvas.drawBitmap(image, null, dest, centerImagePaint)
            canvas.restoreToCount(saveCount)
        } else {
            val baseline = cy - (centerTextPaint.ascent() + centerTextPaint.descent()) / 2f
            canvas.drawText("IMI", cx, baseline, centerTextPaint)
        }
    }

    private fun drawBlips(canvas: Canvas) {
        blips.forEach { blip ->
            val pulse = 1f + 0.15f * pulsePhase
            val dynamicRadius = blip.radius * pulse

            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(colorAccentGlow, 0.28f)
            }
            canvas.drawCircle(blip.x, blip.y, dynamicRadius + dp(5f), halo)

            val nodeImage = deviceNodeBitmap
            if (nodeImage != null) {
                val dest = RectF(
                    blip.x - dynamicRadius,
                    blip.y - dynamicRadius,
                    blip.x + dynamicRadius,
                    blip.y + dynamicRadius
                )
                val saveCount = canvas.save()
                blipClipPath.reset()
                blipClipPath.addCircle(blip.x, blip.y, dynamicRadius, Path.Direction.CW)
                canvas.clipPath(blipClipPath)
                canvas.drawBitmap(nodeImage, null, dest, deviceImagePaint)
                canvas.restoreToCount(saveCount)
            } else {
                canvas.drawCircle(blip.x, blip.y, dynamicRadius, blipPaint)
            }

            canvas.drawCircle(blip.x, blip.y, dynamicRadius, blipStrokePaint)

            canvas.drawText(
                blip.distanceLabel,
                blip.x,
                blip.y - dynamicRadius - dp(8f) + blip.labelYOffset,
                subLabelPaint
            )

            val displayName = if (blip.device.deviceName.length > 10) {
                blip.device.deviceName.take(10) + "..."
            } else {
                blip.device.deviceName
            }
            canvas.drawText(
                displayName,
                blip.x,
                blip.y + dynamicRadius + dp(18f) + blip.labelYOffset,
                labelPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)

        val tapped = blips.firstOrNull { blip ->
            val distance = hypot(event.x - blip.x, event.y - blip.y)
            distance <= blip.radius + dp(10f)
        }

        if (tapped != null) {
            performClick()
            onDeviceClick?.invoke(tapped.device)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun stableAngle(address: String): Float {
        val hash = address.hashCode().toLong() and 0xFFFFFFFFL
        val normalized = (hash % 3600L).toFloat() / 10f
        return (normalized / 180f * PI).toFloat()
    }

    private fun loadCenterGlassesBitmap(): Bitmap? {
        val candidates = listOf(
            "imi glasses image/mart1.png",
            "imi glasses image/mart1_2.png"
        )
        candidates.forEach { assetPath ->
            try {
                context.assets.open(assetPath).use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) return decoded
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun loadDeviceNodeBitmap(): Bitmap? {
        val candidates = listOf(
            "imi glasses image/mart1_2.png",
            "imi glasses image/mart1.png"
        )
        candidates.forEach { assetPath ->
            try {
                context.assets.open(assetPath).use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) return decoded
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun estimateDistanceMeters(rssi: Int): String {
        val txPower = -59.0
        val ratio = rssi.toDouble() / txPower
        val distance = if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            0.89976 * Math.pow(ratio, 7.7095) + 0.111
        }
        return String.format("%.1f", distance.coerceIn(0.6, 30.0))
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}

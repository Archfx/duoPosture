package com.thain.duo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The battery box used in both the outer panels and the hinge strips (peek_battery_box.xml).
 *
 * - Rounded TOP corners, flat bottom (or all four corners via [setRoundAllCorners]).
 * - Background is a bar filling the box horizontally from the left in proportion to the
 *   battery level. Every time the view attaches (each time Peek is shown) the bar animates up
 *   from 0 to the current level; later level changes animate smoothly from where it is.
 * - Base color: green normally, orange below 30%, red below 15%. The color follows the real
 *   level (not the animating bar) and cross-fades when it changes.
 * - While charging:
 *     * the bar's leading edge is a sine wave that keeps moving, and
 *     * the bar pulses between a uniformly brighter version of the base color and a gradient
 *       running from the base color to a lighter version of it (lighter green when green).
 * - Shows the percent, and a battery icon that gains a lightning bolt while plugged in.
 *
 * Children: an ImageView with id peek_battery_icon and a TextView with id peek_battery_text.
 *
 * "Horizontally" and "top" are in this view's own frame. Inside the rotated hinge strip that
 * means the fill grows along the strip and the rounded corners face away from the hinge.
 */
class BatteryBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private companion object {
        const val CORNER_DP = 10f           // keep equal to peek_box_bg.xml
        const val TRACK_COLOR = 0x26FFFFFF  // keep equal to peek_box_bg.xml

        const val GREEN = 0xFF2E7D32.toInt()
        const val ORANGE = 0xFFEF6C00.toInt()
        const val RED = 0xFFC62828.toInt()
        const val ORANGE_BELOW = 30         // level < 30 -> orange
        const val RED_BELOW = 15            // level < 15 -> red

        // Charging look, as how far each color is blended toward white
        const val BRIGHT_AMOUNT = 0.2f      // the solid "brighter base" end of the pulse
        const val LIGHT_AMOUNT = 0.45f      // the light end of the base -> lighter gradient
        const val PULSE_MS = 2000L          // one direction; a full cycle is twice this

        const val WAVE_AMPLITUDE_DP = 3f
        const val WAVE_STEP_DP = 2f         // resolution of the wave outline
        const val WAVE_PERIOD_MS = 1800L    // time for the wave to travel one full period

        const val FILL_MS = 1000L           // 0 -> current level
        const val COLOR_MS = 400L           // color cross-fade
    }

    private val density = resources.displayMetrics.density
    private val corner = CORNER_DP * density
    private val waveAmplitude = WAVE_AMPLITUDE_DP * density
    private val waveStep = WAVE_STEP_DP * density

    private val shape = Path()
    private val wavePath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cached so the gradient is only rebuilt when the bar's length or base color changes
    private var gradientShader: LinearGradient? = null
    private var gradientEdge = -1f
    private var gradientBase = 0

    private var icon: ImageView? = null
    private var percentText: TextView? = null

    private var targetLevel = 0   // the real battery level, 0..100
    private var shownLevel = 0f   // what is currently drawn, 0..100 (animated)
    private var shownColor = GREEN
    private var charging = false
    private var wavePhase = 0f    // radians
    private var pulse = 0f        // 0 = brighter solid base, 1 = base -> lighter gradient

    private var fillAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    private val waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = WAVE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            wavePhase = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setWillNotDraw(false) // ViewGroups skip onDraw by default
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = findViewById(R.id.peek_battery_icon)
        percentText = findViewById(R.id.peek_battery_text)
    }

    /**
     * @param percent 0..100
     * @param isCharging true while actively charging: drives the wave and the gradient pulse
     * @param isPluggedIn true while on a charger (including full): shows the bolt icon
     */
    fun setLevel(percent: Int, isCharging: Boolean, isPluggedIn: Boolean) {
        targetLevel = percent.coerceIn(0, 100)
        charging = isCharging
        percentText?.text = "$targetLevel%"
        icon?.setImageResource(
            if (isPluggedIn) R.drawable.ic_peek_battery_charging else R.drawable.ic_peek_battery
        )
        updateColor(animate = isAttachedToWindow)
        updateChargingAnimations()
        // Before attach, onAttachedToWindow starts the 0 -> level animation instead.
        if (isAttachedToWindow) animateFill()
        invalidate()
    }

    private fun colorForLevel(level: Int) = when {
        level < RED_BELOW -> RED
        level < ORANGE_BELOW -> ORANGE
        else -> GREEN
    }

    private fun updateColor(animate: Boolean) {
        val to = colorForLevel(targetLevel)
        colorAnimator?.cancel()
        if (!animate || to == shownColor) {
            shownColor = to
            return
        }
        colorAnimator = ValueAnimator.ofArgb(shownColor, to).apply {
            duration = COLOR_MS
            addUpdateListener {
                shownColor = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun animateFill() {
        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(shownLevel, targetLevel.toFloat()).apply {
            duration = FILL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateChargingAnimations() {
        if (charging && isAttachedToWindow) {
            if (!waveAnimator.isStarted) waveAnimator.start()
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            waveAnimator.cancel()
            pulseAnimator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shownLevel = 0f // always start from empty
        animateFill()
        updateChargingAnimations()
    }

    override fun onDetachedFromWindow() {
        fillAnimator?.cancel()
        colorAnimator?.cancel()
        waveAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private var roundAll = false

    /** Round all four corners (outer panels) instead of only the top two (hinge strips). */
    fun setRoundAllCorners(all: Boolean) {
        roundAll = all
        buildShape()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShape()
    }

    private fun buildShape() {
        shape.reset()
        val bottom = if (roundAll) corner else 0f
        // Radii order: top-left, top-right, bottom-right, bottom-left (x, y each).
        shape.addRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            floatArrayOf(corner, corner, corner, corner, bottom, bottom, bottom, bottom),
            Path.Direction.CW,
        )
    }

    /**
     * The filled region with a sine-wave right edge: x(y) = edge + A * sin(k*y + phase).
     * One full period spans the box height, and the phase advances over time so the wave
     * travels. The amplitude fades out near empty/full so the bar never spills past the box.
     */
    private fun buildWavePath(edge: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val fade = (min(edge, w - edge) / waveAmplitude).coerceIn(0f, 1f)
        val amp = waveAmplitude * fade
        val k = (2 * PI / h).toFloat()

        fun edgeX(y: Float) = edge + amp * sin(k * y + wavePhase)

        wavePath.reset()
        wavePath.moveTo(0f, 0f)
        var y = 0f
        while (y < h) {
            wavePath.lineTo(edgeX(y), y)
            y += waveStep
        }
        wavePath.lineTo(edgeX(h), h)
        wavePath.lineTo(0f, h)
        wavePath.close()
    }

    /** Blends [color] toward white by [amount] (0 = unchanged, 1 = white). */
    private fun lighten(color: Int, amount: Float): Int {
        fun channel(c: Int) = c + ((255 - c) * amount).toInt()
        return Color.argb(255, channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
    }

    /** Gradient from the base color at the bar's left to a lighter version of it at its edge. */
    private fun gradientFor(edge: Float, base: Int): LinearGradient {
        val cached = gradientShader
        if (cached != null && edge == gradientEdge && base == gradientBase) return cached
        return LinearGradient(
            0f, 0f, max(edge, 1f), 0f,
            base, lighten(base, LIGHT_AMOUNT),
            Shader.TileMode.CLAMP,
        ).also {
            gradientShader = it
            gradientEdge = edge
            gradientBase = base
        }
    }

    override fun onDraw(canvas: Canvas) {
        // empty track
        paint.color = TRACK_COLOR
        canvas.drawPath(shape, paint)

        val edge = width * shownLevel / 100f
        canvas.save()
        canvas.clipPath(shape)
        if (charging && edge > 0f) {
            buildWavePath(edge)
            // Pulse between a uniformly brighter base and a base -> lighter gradient. Painting the
            // gradient over the opaque brighter base at alpha = pulse is an exact cross-fade.
            paint.color = lighten(shownColor, BRIGHT_AMOUNT)
            canvas.drawPath(wavePath, paint)
            gradientPaint.shader = gradientFor(edge, shownColor)
            gradientPaint.alpha = (pulse * 255).toInt()
            canvas.drawPath(wavePath, gradientPaint)
        } else {
            paint.color = shownColor
            canvas.drawRect(0f, 0f, edge, height.toFloat(), paint)
        }
        canvas.restore()
    }
}
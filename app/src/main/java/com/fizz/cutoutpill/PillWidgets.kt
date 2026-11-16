package com.fizz.cutoutpill

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class PillColors(
    val active: Int,
    val inactive: Int,
    val iconOnInactive: Int,
    val button: Int,
    val iconOnButton: Int,
    val background: Int
) {
    companion object {
        @android.annotation.SuppressLint("NewApi")
        fun of(context: Context): PillColors {
            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val s = Build.VERSION.SDK_INT >= 31
            fun c(id: Int, fallback: Int): Int = if (s) ContextCompat.getColor(context, id) else fallback

            return if (isDark) {
                PillColors(
                    active = c(android.R.color.system_accent1_200, Color.parseColor("#FFB4AB")),
                    inactive = c(android.R.color.system_accent1_800, Color.parseColor("#410002")),
                    iconOnInactive = c(android.R.color.system_accent1_100, Color.parseColor("#FFDAD6")),
                    button = c(android.R.color.system_accent1_700, Color.parseColor("#690005")),
                    iconOnButton = c(android.R.color.system_accent1_100, Color.parseColor("#FFDAD6")),
                    background = Color.BLACK
                )
            } else {
                PillColors(
                    active = c(android.R.color.system_accent1_600, Color.parseColor("#B3261E")),
                    inactive = c(android.R.color.system_accent1_100, Color.parseColor("#FFDAD6")),
                    iconOnInactive = c(android.R.color.system_accent1_600, Color.parseColor("#B3261E")),
                    button = c(android.R.color.system_accent1_200, Color.parseColor("#FFB4AB")),
                    iconOnButton = c(android.R.color.system_accent1_900, Color.parseColor("#410002")),
                    background = Color.WHITE
                )
            }
        }
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.argb(
    lerpF(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), t).toInt(),
    lerpF(Color.red(a).toFloat(), Color.red(b).toFloat(), t).toInt(),
    lerpF(Color.green(a).toFloat(), Color.green(b).toFloat(), t).toInt(),
    lerpF(Color.blue(a).toFloat(), Color.blue(b).toFloat(), t).toInt()
)

/* ------------------------------------------------------------------------------------------
 * Auto-brightness button
 *   OFF: plain circle in the track colour, outlined badge icon
 *   ON : soft four-lobed "cookie" in the active colour, filled badge icon
 * Toggling morphs circle <-> cookie (with a little rotation), fades the colour and icon.
 * ---------------------------------------------------------------------------------------- */

private const val COOKIE_STEPS = 180

/** Radius of the cookie outline per angle, normalised so the side midpoints touch radius 1. */
private val COOKIE_RADII = FloatArray(COOKIE_STEPS + 1) { i ->
    val th = 2.0 * PI * i / COOKIE_STEPS
    val n = 2.2      // how square the base shape is
    val d = 0.15     // depth of the dip in the middle of each side
    val p = 3.0      // how wide that dip is (higher = narrower)
    val c = abs(cos(th))
    val s = abs(sin(th))
    val squircle = (c.pow(n) + s.pow(n)).pow(-1.0 / n)
    (squircle * (1.0 - d * abs(cos(2.0 * th)).pow(p)) / (1.0 - d)).toFloat()
}

class ModeButton(context: Context, private val colors: PillColors) : View(context) {

    /** Setting this animates the morph. Before the view is on screen it just jumps. */
    var auto: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    private var t = 0f   // 0 = OFF look, 1 = ON look (can overshoot slightly while animating)
    private var anim: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        pathEffect = CornerPathEffect(1f)
    }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val shapePath = Path()
    private val badgePath = Path()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun animateTo(target: Float) {
        anim?.cancel()
        if (!isAttachedToWindow) {
            t = target
            invalidate()
            return
        }
        val turningOn = target > t
        anim = ValueAnimator.ofFloat(t, target).apply {
            duration = 380
            interpolator = if (turningOn) OvershootInterpolator(1.3f) else DecelerateInterpolator(1.5f)
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun buildBadge(radius: Float) {
        // 8-point rounded star, like the Material "brightness auto" badge
        badgePath.rewind()
        val points = 16
        for (i in 0 until points) {
            val a = i * PI / (points / 2) - PI / 2
            val r = if (i % 2 == 0) radius else radius * 0.80f
            val x = (r * cos(a)).toFloat()
            val y = (r * sin(a)).toFloat()
            if (i == 0) badgePath.moveTo(x, y) else badgePath.lineTo(x, y)
        }
        badgePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val tc = t.coerceIn(0f, 1f)
        val base = min(width, height) / 2f

        // --- body: circle -> cookie, rotating a bit while it morphs ---
        val rot = (1f - tc) * -0.6f
        val shrink = 1f - 0.03f * tc            // keep the lobes inside the view
        shapePath.rewind()
        for (i in 0..COOKIE_STEPS) {
            val th = 2.0 * PI * i / COOKIE_STEPS
            val r = base * shrink * lerpF(1f, COOKIE_RADII[i], t)
            val a = th + rot
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
        }
        shapePath.close()
        fillPaint.color = lerpColor(colors.inactive, colors.active, tc)
        canvas.drawPath(shapePath, fillPaint)

        // --- icon: outlined badge when OFF, filled badge with a cut-out A when ON ---
        val badgeR = base * 0.42f
        buildBadge(badgeR)
        badgeFill.pathEffect = CornerPathEffect(badgeR * 0.2f)
        badgeStroke.pathEffect = CornerPathEffect(badgeR * 0.2f)
        badgeStroke.strokeWidth = dp(1.8f)

        canvas.save()
        canvas.translate(cx, cy)
        // ON: dark filled badge
        badgeFill.color = colors.inactive
        badgeFill.alpha = (tc * 255).toInt()
        if (badgeFill.alpha > 0) canvas.drawPath(badgePath, badgeFill)
        // OFF: light outline
        badgeStroke.color = colors.iconOnInactive
        badgeStroke.alpha = ((1f - tc) * 255).toInt()
        if (badgeStroke.alpha > 0) canvas.drawPath(badgePath, badgeStroke)
        canvas.restore()

        letterPaint.textSize = badgeR * 1.15f
        letterPaint.color = lerpColor(colors.iconOnInactive, colors.active, tc)
        val baseline = cy - (letterPaint.ascent() + letterPaint.descent()) / 2f
        canvas.drawText("A", cx, baseline, letterPaint)
    }
}

/* ------------------------------------------------------------------------------------------
 * Brightness slider
 *   [ filled part ] | [ unfilled part . ]
 *   - filled part: big round corners on the left, small ones next to the handle, sun icon
 *   - handle: thin vertical bar that gets thinner while the finger is down
 *   - unfilled part: small round corners next to the handle, big on the right, small dot at the end
 *   - sun icon fades out when the filled part gets too narrow, the dot fades when the handle arrives
 * ---------------------------------------------------------------------------------------- */

class ExpressiveSlider(context: Context, private val colors: PillColors) : View(context) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 254)
            invalidate()
        }

    var onUserChange: ((Int) -> Unit)? = null

    private var pressT = 0f          // 0 = finger up, 1 = finger down (handle thin)
    private var pressAnim: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.inactive }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.active }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.inactive
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.inactive }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.active }

    private val leftPath = Path()
    private val rightPath = Path()
    private val handleRect = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressT, target).apply {
            duration = 140
            addUpdateListener {
                pressT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f

        val trackH = dp(52f)
        val top = cy - trackH / 2f
        val bottom = cy + trackH / 2f
        val handleW = lerpF(dp(6f), dp(3f), pressT)
        val handleH = trackH + dp(8f)
        val gap = dp(6f)
        val outerR = dp(20f)
        val innerR = dp(4f)

        // handle travels so it stays fully inside the view at 0% and 100%
        val half = dp(3f)
        val fraction = progress / 254f
        val cx = lerpF(half, w - half, fraction)

        // ---- filled part ----
        val rx = cx - gap - handleW / 2f
        if (rx > dp(1f)) {
            leftPath.rewind()
            leftPath.addRoundRect(
                0f, top, rx, bottom,
                floatArrayOf(outerR, outerR, innerR, innerR, innerR, innerR, outerR, outerR),
                Path.Direction.CW
            )
            canvas.drawPath(leftPath, progressPaint)
        }

        // ---- unfilled part ----
        val lx = cx + gap + handleW / 2f
        if (w - lx > dp(1f)) {
            rightPath.rewind()
            rightPath.addRoundRect(
                lx, top, w, bottom,
                floatArrayOf(innerR, innerR, outerR, outerR, outerR, outerR, innerR, innerR),
                Path.Direction.CW
            )
            canvas.drawPath(rightPath, trackPaint)

            // small dot at the end of the track, fades as the handle gets close
            val dotCx = w - dp(22f)
            val dotAlpha = ((dotCx - lx - dp(6f)) / dp(14f)).coerceIn(0f, 1f)
            if (dotAlpha > 0f) {
                dotPaint.alpha = (dotAlpha * 255).toInt()
                canvas.drawCircle(dotCx, cy, dp(2.2f), dotPaint)
            }
        }

        // ---- handle ----
        handleRect.set(cx - handleW / 2f, cy - handleH / 2f, cx + handleW / 2f, cy + handleH / 2f)
        canvas.drawRoundRect(handleRect, handleW / 2f, handleW / 2f, progressPaint)

        // ---- sun icon inside the filled part ----
        val iconR = dp(11f)
        val iconCx = dp(26f)
        val iconAlpha = ((rx - (iconCx - iconR)) / (iconR * 2f + dp(6f))).coerceIn(0f, 1f)
        if (iconAlpha > 0f) {
            val scale = 0.6f + 0.4f * iconAlpha
            val a = (iconAlpha * 255).toInt()
            iconFill.alpha = a
            iconPaint.alpha = a
            iconPaint.strokeWidth = dp(1.8f) * scale
            canvas.drawCircle(iconCx, cy, iconR * 0.42f * scale, iconFill)
            for (i in 0 until 8) {
                val ang = i * PI / 4
                val c = cos(ang).toFloat()
                val s = sin(ang).toFloat()
                val r1 = iconR * 0.68f * scale
                val r2 = iconR * scale
                canvas.drawLine(iconCx + c * r1, cy + s * r1, iconCx + c * r2, cy + s * r2, iconPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.action == MotionEvent.ACTION_DOWN) animatePress(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                val half = dp(3f)
                val span = (width - 2f * half).coerceAtLeast(1f)
                val fraction = ((event.x - half) / span).coerceIn(0f, 1f)
                val newProgress = (fraction * 254).toInt()
                if (newProgress != progress) {
                    progress = newProgress
                    onUserChange?.invoke(progress)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
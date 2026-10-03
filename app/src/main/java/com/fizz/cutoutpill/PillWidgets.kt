package com.fizz.cutoutpill

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

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

class ModeButton(context: Context, private val colors: PillColors) : View(context) {
    var auto: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private val iconAuto: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_brightness_auto)
    private val iconManual: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_brightness_medium)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        
        paint.color = if (auto) colors.active else colors.button
        val cornerRadius = 16f * resources.displayMetrics.density
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, paint)

        val icon = if (auto) iconAuto else iconManual
        if (icon != null) {
            val iconSize = (24 * resources.displayMetrics.density).toInt()
            val cx = width / 2
            val cy = height / 2
            icon.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
            icon.setTint(if (auto) colors.inactive else colors.iconOnButton)
            icon.draw(canvas)
        }
    }
}

class ExpressiveSlider(context: Context, private val colors: PillColors) : View(context) {
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 254)
            invalidate()
        }

    var onUserChange: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.inactive }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.active }
    
    private val leftPath = Path()
    private val rightPath = Path()
    private val handleRect = RectF()
    
    private val icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_brightness_medium)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val trackH = dp(44f)
        val handleW = dp(4f)
        val handleH = trackH + dp(14f)
        val gap = dp(6f)
        
        val fraction = progress / 254f
        val cx = fraction * w
        
        val cy = h / 2f
        val top = cy - trackH / 2f
        val bottom = cy + trackH / 2f
        
        val outerR = dp(16f)
        val innerR = dp(4f)

        // Left part (filled)
        val rx = cx - gap - handleW / 2f
        if (rx > 0f) {
            leftPath.reset()
            leftPath.addRoundRect(
                0f, top, rx, bottom,
                floatArrayOf(outerR, outerR, innerR, innerR, innerR, innerR, outerR, outerR),
                Path.Direction.CW
            )
            canvas.drawPath(leftPath, progressPaint)
        }

        // Right part (empty)
        val lx = cx + gap + handleW / 2f
        if (lx < w) {
            rightPath.reset()
            rightPath.addRoundRect(
                lx, top, w, bottom,
                floatArrayOf(innerR, innerR, outerR, outerR, outerR, outerR, innerR, innerR),
                Path.Direction.CW
            )
            canvas.drawPath(rightPath, trackPaint)
        }

        // Handle
        val hTop = cy - handleH / 2f
        val hBottom = cy + handleH / 2f
        handleRect.set(cx - handleW / 2f, hTop, cx + handleW / 2f, hBottom)
        canvas.drawRoundRect(handleRect, handleW / 2f, handleW / 2f, progressPaint)

        // Icon
        if (icon != null) {
            val iconSize = dp(24f).toInt()
            val iconCx = w - outerR
            val iconCy = cy
            icon.setBounds(
                (iconCx - iconSize / 2f).toInt(),
                (iconCy - iconSize / 2f).toInt(),
                (iconCx + iconSize / 2f).toInt(),
                (iconCy + iconSize / 2f).toInt()
            )
            // switches to the "on active" color (which is 'inactive' color for contrast) when filled part reaches it
            val isFilled = cx >= iconCx
            icon.setTint(if (isFilled) colors.inactive else colors.iconOnInactive)
            icon.draw(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val fraction = (event.x / width).coerceIn(0f, 1f)
                val newProgress = (fraction * 254).toInt()
                if (newProgress != progress) {
                    progress = newProgress
                    onUserChange?.invoke(progress)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

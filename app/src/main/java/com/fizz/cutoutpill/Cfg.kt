package com.fizz.cutoutpill

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.WindowManager

/** Shared settings: the "cutout area" (center x/y + width/height in px). */
object Cfg {
    const val CX = "cx"
    const val CY = "cy"
    const val W = "w"
    const val H = "h"
    const val CALIBRATING = "calibrating"

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** Auto-detected values from the system: [cx, cy, w, h]. */
    private fun detected(c: Context): IntArray {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = wm.currentWindowMetrics
        val r = m.windowInsets.displayCutout?.boundingRectTop
        return if (r != null && !r.isEmpty)
            intArrayOf(r.centerX(), r.centerY(), r.width(), r.height())
        else
            intArrayOf(m.bounds.width() / 2, dp(c, 16), dp(c, 32), dp(c, 32))
    }

    /** Saved value if the user changed it, otherwise the auto-detected one. */
    fun get(c: Context): IntArray {
        val d = detected(c)
        val p = prefs(c)
        return intArrayOf(
            p.getInt(CX, d[0]), p.getInt(CY, d[1]),
            p.getInt(W, d[2]), p.getInt(H, d[3])
        )
    }

    /** Touch area = show area = this rect. */
    fun rect(c: Context): Rect {
        val (cx, cy, w, h) = get(c)
        val top = (cy - h / 2).coerceAtLeast(0)
        return Rect(cx - w / 2, top, cx + w / 2, top + h)
    }

    fun reset(c: Context) {
        prefs(c).edit().remove(CX).remove(CY).remove(W).remove(H).apply()
    }
}

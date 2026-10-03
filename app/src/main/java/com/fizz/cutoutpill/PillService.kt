package com.fizz.cutoutpill

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min

class PillService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var hotspot: View? = null
    private var preview: View? = null
    
    private var pillDialog: Dialog? = null
    private var pillContainer: FrameLayout? = null
    private var pillContent: View? = null
    private var windowBgDrawable: GradientDrawable? = null
    
    private var isPillVisible = false
    private var isOpening = false
    private var shapeFraction = 0f
    private var crossfadeFraction = 0f
    private var currentAnimator: ValueAnimator? = null
    private var needsRebuildOnClose = false

    private val localCutout = RectF()
    private val localPill = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hidePill() }

    // must be a field: SharedPreferences only keeps a weak reference
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshOverlays()
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Cfg.prefs(this)
        prefs.edit().putBoolean(Cfg.CALIBRATING, false).apply()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        refreshOverlays()
        if (!isPillVisible) {
            pillDialog?.dismiss()
            pillDialog = null
            pillContainer = null
            pillContent = null
            windowBgDrawable = null
        } else {
            needsRebuildOnClose = true
        }
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        hidePill(animate = false)
        pillDialog?.dismiss()
        pillDialog = null
        pillContainer = null
        pillContent = null
        windowBgDrawable = null
        removeView(hotspot); hotspot = null
        removeView(preview); preview = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun removeView(v: View?) { v?.let { runCatching { wm.removeView(it) } } }

    // ---------- overlays driven by the saved settings ----------

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int,
                              touchable: Boolean, watchOutside: Boolean = false) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun upsert(existing: View?, r: Rect, touchable: Boolean, make: () -> View): View {
        val lp = overlayParams(r.width(), r.height(), r.left, r.top, touchable)
        return if (existing != null) {
            wm.updateViewLayout(existing, lp); existing
        } else make().also { wm.addView(it, lp) }
    }

    /** Called on connect, rotation, and whenever a setting changes. */
    private fun refreshOverlays() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            removeView(hotspot); hotspot = null
            removeView(preview); preview = null
            return
        }
        val r = Cfg.rect(this)
        if (prefs.getBoolean(Cfg.CALIBRATING, false)) {
            removeView(hotspot); hotspot = null
            preview = upsert(preview, r, false) {
                View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(0xFFFF4D6D.toInt())
                        cornerRadius = 1000f   // clamps to a full circle/pill
                    }
                }
            }
        } else {
            removeView(preview); preview = null
            hotspot = upsert(hotspot, r, true) {
                View(this).apply {
                    setBackgroundColor(Color.argb(1, 0, 0, 0))   // invisible but touchable
                    setOnClickListener { if (!isPillVisible) showPill() else hidePill() }
                }
            }
        }
    }

    private fun lerp(start: Float, stop: Float, fraction: Float): Float {
        return start + (stop - start) * fraction
    }

    private fun lerpColor(colorStart: Int, colorEnd: Int, fraction: Float): Int {
        val aStart = Color.alpha(colorStart)
        val rStart = Color.red(colorStart)
        val gStart = Color.green(colorStart)
        val bStart = Color.blue(colorStart)
        
        val aEnd = Color.alpha(colorEnd)
        val rEnd = Color.red(colorEnd)
        val gEnd = Color.green(colorEnd)
        val bEnd = Color.blue(colorEnd)
        
        return Color.argb(
            (aStart + (aEnd - aStart) * fraction).toInt(),
            (rStart + (rEnd - rStart) * fraction).toInt(),
            (gStart + (gEnd - gStart) * fraction).toInt(),
            (bStart + (bEnd - bStart) * fraction).toInt()
        )
    }

    private fun updatePillVisuals() {
        val container = pillContainer ?: return
        val dialog = pillDialog ?: return

        container.invalidateOutline()
        pillContent?.alpha = ((shapeFraction - 0.5f) * 2f).coerceIn(0f, 1f)

        val blurSupported = android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
        val bgAlpha = if (blurSupported) 200 else 242
        val baseColor = PillColors.of(this).background
        val targetColor = Color.argb(bgAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        val colorFraction = (shapeFraction / 0.3f).coerceIn(0f, 1f)
        val viewBaseColor = lerpColor(Color.BLACK, targetColor, colorFraction)

        val viewAlphaMultiplier = 1f - crossfadeFraction
        val viewFinalColor = Color.argb(
            (Color.alpha(viewBaseColor) * viewAlphaMultiplier).toInt(),
            Color.red(viewBaseColor),
            Color.green(viewBaseColor),
            Color.blue(viewBaseColor)
        )
        (container.background as? ColorDrawable)?.color = viewFinalColor

        windowBgDrawable?.setColor(targetColor)
        val winBgAlpha = (crossfadeFraction * 255).toInt()
        windowBgDrawable?.alpha = winBgAlpha

        if (blurSupported) {
            dialog.window?.attributes = dialog.window?.attributes?.apply {
                blurBehindRadius = (crossfadeFraction * 24).toInt()
            }
        }
    }

    private fun getPillDialog(pillW: Int, pillH: Int, margin: Int, top: Int): Dialog {
        var dialog = pillDialog
        if (dialog == null) {
            dialog = object : Dialog(this, android.R.style.Theme_Translucent_NoTitleBar) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) hidePill()
                    return super.onTouchEvent(event)
                }
            }
            val win = dialog.window!!
            win.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            win.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setGravity(Gravity.TOP or Gravity.START)
            
            val attrs = win.attributes
            attrs.x = margin
            attrs.y = top
            attrs.width = pillW
            attrs.height = pillH
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            
            val blurSupported = android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
            if (blurSupported) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attrs.blurBehindRadius = 0
            }
            
            windowBgDrawable = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                alpha = 0
            }
            win.setBackgroundDrawable(windowBgDrawable)
            win.attributes = attrs

            val content = buildPill()
            pillContent = content
            val fl = object : FrameLayout(this) {}
            fl.clipToOutline = true
            fl.background = ColorDrawable(Color.BLACK)
            fl.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            
            fl.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val currentRect = RectF(
                        lerp(localCutout.left, localPill.left, shapeFraction),
                        lerp(localCutout.top, localPill.top, shapeFraction),
                        lerp(localCutout.right, localPill.right, shapeFraction),
                        lerp(localCutout.bottom, localPill.bottom, shapeFraction)
                    )
                    val radius = min(currentRect.width(), currentRect.height()) / 2f
                    outline.setRoundRect(
                        currentRect.left.toInt(),
                        currentRect.top.toInt(),
                        currentRect.right.toInt(),
                        currentRect.bottom.toInt(),
                        radius
                    )
                }
            }

            dialog.setContentView(fl)
            pillContainer = fl
            pillDialog = dialog
        }
        return dialog
    }

    private fun startNextOpenPhase() {
        if (shapeFraction < 1f) {
            val duration = ((1f - shapeFraction) * 350).toLong()
            currentAnimator = ValueAnimator.ofFloat(shapeFraction, 1f).apply {
                this.duration = duration
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener {
                    shapeFraction = it.animatedValue as Float
                    updatePillVisuals()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (isOpening) startNextOpenPhase()
                    }
                })
                start()
            }
        } else if (crossfadeFraction < 1f) {
            val duration = ((1f - crossfadeFraction) * 120).toLong()
            currentAnimator = ValueAnimator.ofFloat(crossfadeFraction, 1f).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener {
                    crossfadeFraction = it.animatedValue as Float
                    updatePillVisuals()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        pillContainer?.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                })
                start()
            }
        } else {
            pillContainer?.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun startNextClosePhase() {
        if (crossfadeFraction > 0f) {
            val duration = (crossfadeFraction * 120).toLong()
            currentAnimator = ValueAnimator.ofFloat(crossfadeFraction, 0f).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener {
                    crossfadeFraction = it.animatedValue as Float
                    updatePillVisuals()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isOpening) startNextClosePhase()
                    }
                })
                start()
            }
        } else if (shapeFraction > 0f) {
            val duration = (shapeFraction * 300).toLong()
            currentAnimator = ValueAnimator.ofFloat(shapeFraction, 0f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    shapeFraction = it.animatedValue as Float
                    updatePillVisuals()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isOpening) {
                            pillContainer?.setLayerType(View.LAYER_TYPE_NONE, null)
                            pillDialog?.dismiss()
                            if (needsRebuildOnClose) {
                                needsRebuildOnClose = false
                                pillDialog = null
                                pillContainer = null
                                pillContent = null
                                windowBgDrawable = null
                            }
                        }
                    }
                })
                start()
            }
        } else {
            pillContainer?.setLayerType(View.LAYER_TYPE_NONE, null)
            pillDialog?.dismiss()
            if (needsRebuildOnClose) {
                needsRebuildOnClose = false
                pillDialog = null
                pillContainer = null
                pillContent = null
                windowBgDrawable = null
            }
        }
    }

    private fun showPill() {
        if (isPillVisible) return
        isPillVisible = true
        resetTimer()

        val r = Cfg.rect(this)
        val screenW = wm.currentWindowMetrics.bounds.width()
        val margin = dp(14)
        val top = dp(14)
        val pillW = screenW - margin * 2
        val pillH = dp(116)
        
        val dialog = getPillDialog(pillW, pillH, margin, top)

        localCutout.set(
            (r.left - margin).toFloat(),
            (r.top - top).toFloat(),
            (r.right - margin).toFloat(),
            (r.bottom - top).toFloat()
        )
        localPill.set(0f, 0f, pillW.toFloat(), pillH.toFloat())

        currentAnimator?.cancel()
        
        if (!dialog.isShowing) {
            shapeFraction = 0f
            crossfadeFraction = 0f
            updatePillVisuals()
            dialog.show()
        }
        
        pillContainer?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isOpening = true
        startNextOpenPhase()
    }

    private fun hidePill(animate: Boolean = true) {
        handler.removeCallbacks(autoHide)
        if (!isPillVisible) return
        isPillVisible = false
        
        currentAnimator?.cancel()
        
        if (!animate) {
            shapeFraction = 0f
            crossfadeFraction = 0f
            pillDialog?.dismiss()
            if (needsRebuildOnClose) {
                needsRebuildOnClose = false
                pillDialog = null
                pillContainer = null
                pillContent = null
                windowBgDrawable = null
            }
            return
        }
        
        pillContainer?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isOpening = false
        startNextClosePhase()
    }

    private fun resetTimer() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 4000)
    }

    private var askedWrite = false

    private fun openWriteSettings() {
        if (askedWrite) return
        askedWrite = true
        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isAuto() = Settings.System.getInt(
        contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    private fun buildPill(): View {
        askedWrite = false
        val colors = PillColors.of(this)   // theme + wallpaper palette

        val bg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }

        val modeBtn = ModeButton(this, colors).apply { auto = isAuto() }

        val slider = ExpressiveSlider(this, colors).apply {
            progress = (Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) - 1).coerceIn(0, 254)
            onUserChange = { p ->
                resetTimer()
                if (!Settings.System.canWrite(this@PillService)) {
                    openWriteSettings()
                } else {
                    Settings.System.putInt(contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, p + 1)
                    modeBtn.auto = false
                }
            }
        }

        modeBtn.setOnClickListener {
            resetTimer()
            if (!Settings.System.canWrite(this)) { openWriteSettings(); return@setOnClickListener }
            val toAuto = !modeBtn.auto
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (toAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            modeBtn.auto = toAuto
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(28), dp(20), dp(28), dp(0))
            addView(slider, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(modeBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(14) })
        }
    }
}

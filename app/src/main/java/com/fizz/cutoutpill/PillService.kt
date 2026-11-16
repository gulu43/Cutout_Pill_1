package com.fizz.cutoutpill

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlin.math.min

class PillService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var hotspot: View? = null
    private var preview: View? = null
    
    // Instead of a Dialog, we use a pure WindowManager View structure
    private var pillRootView: FrameLayout? = null
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

    // Brightness write throttle state
    private var lastBrightnessWrite = 0L
    private var pendingBrightness = -1
    private val brightnessWriteRunnable = Runnable {
        if (pendingBrightness != -1) {
            writeBrightness(pendingBrightness)
            pendingBrightness = -1
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshOverlays()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    hidePill(animate = false)
                    removeView(hotspot); hotspot = null
                    removeView(preview); preview = null
                }
                Intent.ACTION_SCREEN_ON -> refreshOverlays()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Cfg.prefs(this)
        prefs.edit().putBoolean(Cfg.CALIBRATING, false).apply()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createAndStartForeground()
        refreshOverlays()
        return START_STICKY
    }

    private fun createAndStartForeground() {
        val channelId = "pill_service_channel"
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Cutout Pill Service", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps the invisible camera cutout overlay active."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cutout Pill Active")
            .setContentText("Tap the camera cutout to adjust brightness.")
            .setSmallIcon(R.mipmap.ic_launcher) // Note: using default mipmap. Replace if you have a custom notification icon.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshOverlays()
        if (!isPillVisible) {
            removePillWindow()
        } else {
            needsRebuildOnClose = true
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        hidePill(animate = false)
        removeView(hotspot); hotspot = null
        removeView(preview); preview = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun removeView(v: View?) { v?.let { runCatching { wm.removeView(it) } } }

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int, touchable: Boolean, watchOutside: Boolean = false) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun upsert(existing: View?, r: Rect, touchable: Boolean, make: () -> View): View {
        val lp = overlayParams(r.width(), r.height(), r.left, r.top, touchable)
        return if (existing != null) {
            wm.updateViewLayout(existing, lp); existing
        } else make().also { wm.addView(it, lp) }
    }

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
                        cornerRadius = 1000f
                    }
                }
            }
        } else {
            removeView(preview); preview = null
            hotspot = upsert(hotspot, r, true) {
                View(this).apply {
                    setBackgroundColor(Color.argb(1, 0, 0, 0))
                    setOnClickListener { if (!isPillVisible) showPill() else hidePill() }
                }
            }
        }
    }

    private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

    private fun lerpColor(colorStart: Int, colorEnd: Int, fraction: Float): Int {
        return Color.argb(
            (Color.alpha(colorStart) + (Color.alpha(colorEnd) - Color.alpha(colorStart)) * fraction).toInt(),
            (Color.red(colorStart) + (Color.red(colorEnd) - Color.red(colorStart)) * fraction).toInt(),
            (Color.green(colorStart) + (Color.green(colorEnd) - Color.green(colorStart)) * fraction).toInt(),
            (Color.blue(colorStart) + (Color.blue(colorEnd) - Color.blue(colorStart)) * fraction).toInt()
        )
    }

    private fun updatePillVisuals() {
        val container = pillContainer ?: return
        val root = pillRootView ?: return

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
        windowBgDrawable?.alpha = (crossfadeFraction * 255).toInt()

        // Efficiently update Window LayoutParams only when blur radius changes
        if (blurSupported) {
            val lp = root.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                val newRadius = (crossfadeFraction * 24).toInt()
                if (lp.blurBehindRadius != newRadius) {
                    lp.blurBehindRadius = newRadius
                    if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
                }
            }
        }
    }

    private fun getPillWindow(pillW: Int, pillH: Int, margin: Int, top: Int): FrameLayout {
        var root = pillRootView
        if (root == null) {
            root = object : FrameLayout(this) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.action == MotionEvent.ACTION_OUTSIDE) { hidePill(); return true }
                    return super.dispatchTouchEvent(ev)
                }
            }
            val lp = overlayParams(pillW, pillH, margin, top, touchable = true, watchOutside = true)
            
            val blurSupported = android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
            if (blurSupported) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = 0
            }
            
            windowBgDrawable = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                alpha = 0
            }
            root.background = windowBgDrawable

            val content = buildPill()
            pillContent = content
            val fl = FrameLayout(this)
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
                        currentRect.left.toInt(), currentRect.top.toInt(),
                        currentRect.right.toInt(), currentRect.bottom.toInt(), radius
                    )
                }
            }

            root.addView(fl, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            pillContainer = fl
            pillRootView = root
            
            shapeFraction = 0f
            crossfadeFraction = 0f
            updatePillVisuals()
            wm.addView(root, lp)
        }
        return root
    }

    private fun removePillWindow() {
        pillRootView?.let { 
            runCatching { wm.removeView(it) } 
            pillRootView = null
        }
        pillContainer = null
        pillContent = null
        windowBgDrawable = null
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
                            removePillWindow()
                            if (needsRebuildOnClose) needsRebuildOnClose = false
                        }
                    }
                })
                start()
            }
        } else {
            pillContainer?.setLayerType(View.LAYER_TYPE_NONE, null)
            removePillWindow()
            if (needsRebuildOnClose) needsRebuildOnClose = false
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
        
        getPillWindow(pillW, pillH, margin, top)

        localCutout.set(
            (r.left - margin).toFloat(), (r.top - top).toFloat(),
            (r.right - margin).toFloat(), (r.bottom - top).toFloat()
        )
        localPill.set(0f, 0f, pillW.toFloat(), pillH.toFloat())

        currentAnimator?.cancel()
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
            removePillWindow()
            needsRebuildOnClose = false
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

    private fun writeBrightness(value: Int) {
        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        } catch (e: Exception) {
            // Ignored, might lack permission
        }
    }

    private fun buildPill(): View {
        askedWrite = false
        val colors = PillColors.of(this)
        val modeBtn = ModeButton(this, colors).apply { auto = isAuto() }

        val slider = ExpressiveSlider(this, colors).apply {
            progress = (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) - 1).coerceIn(0, 254)
            onUserChange = { p ->
                resetTimer()
                if (!Settings.System.canWrite(this@PillService)) {
                    openWriteSettings()
                } else {
                    pendingBrightness = p + 1
                    modeBtn.auto = false
                    val now = System.currentTimeMillis()
                    // Throttling brightness updates
                    if (now - lastBrightnessWrite > 100) {
                        handler.removeCallbacks(brightnessWriteRunnable)
                        writeBrightness(pendingBrightness)
                        pendingBrightness = -1
                        lastBrightnessWrite = now
                    } else {
                        handler.removeCallbacks(brightnessWriteRunnable)
                        handler.postDelayed(brightnessWriteRunnable, 100)
                    }
                }
            }
        }

        modeBtn.setOnClickListener {
            resetTimer()
            if (!Settings.System.canWrite(this)) { openWriteSettings(); return@setOnClickListener }
            val toAuto = !modeBtn.auto
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (toAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            modeBtn.auto = toAuto
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setPadding(dp(28), dp(20), dp(28), dp(0))
            addView(slider, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(modeBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(14) })
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = Cfg.prefs(context)
            if (prefs.getBoolean("service_enabled", false) && Settings.canDrawOverlays(context)) {
                context.startForegroundService(Intent(context, PillService::class.java))
            }
        }
    }
}
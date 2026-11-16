package com.fizz.cutoutpill

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlin.math.min
import kotlin.math.roundToInt

class PillService : Service() {

    companion object {
        private const val PILL_CORNER_DP = 28   // final corner radius of the open pill
        private const val PILL_ALPHA = 242      // 0-255, pill opacity (242 = ~95%)
        private const val OPEN_MS = 380L
        private const val CLOSE_MS = 280L
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var hotspot: View? = null
    private var preview: View? = null
    
    // Instead of a Dialog, we use a pure WindowManager View structure
    private var pillRootView: FrameLayout? = null
    private var pillContainer: FrameLayout? = null
    private var pillContent: View? = null

    private var isPillVisible = false
    private var shapeFraction = 0f      // 0 = a 1px dot at the tap-area centre, 1 = full pill
    private var currentAnimator: ValueAnimator? = null

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
                    closePillImmediately()
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
        if (!isPillVisible) removePillWindow()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        closePillImmediately()
        handler.removeCallbacksAndMessages(null)
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

    private fun updatePillVisuals() {
        pillContainer?.invalidateOutline()
        // content fades in late on open and out early on close
        pillContent?.alpha = ((shapeFraction - 0.45f) / 0.55f).coerceIn(0f, 1f)
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

            val content = buildPill()
            pillContent = content

            // ONE solid layer in the theme colour (white in light theme, black in dark).
            // The shape comes only from the outline below, so there is no second layer
            // to crossfade and nothing see-through or differently rounded.
            val base = PillColors.of(this).background
            val fl = FrameLayout(this)
            fl.clipToOutline = true
            fl.background = ColorDrawable(
                Color.argb(PILL_ALPHA, Color.red(base), Color.green(base), Color.blue(base))
            )
            fl.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

            val maxRadius = dp(PILL_CORNER_DP).toFloat()
            fl.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val l = lerp(localCutout.left, localPill.left, shapeFraction).roundToInt()
                    val t = lerp(localCutout.top, localPill.top, shapeFraction).roundToInt()
                    var r = lerp(localCutout.right, localPill.right, shapeFraction).roundToInt()
                    var b = lerp(localCutout.bottom, localPill.bottom, shapeFraction).roundToInt()
                    if (r <= l) r = l + 1          // never an empty outline
                    if (b <= t) b = t + 1
                    // fully round while small, then settles at PILL_CORNER_DP with no jump
                    val radius = min(min((r - l), (b - t)) / 2f, maxRadius)
                    outline.setRoundRect(l, t, r, b, radius)
                }
            }

            root.addView(fl, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            pillContainer = fl
            pillRootView = root

            shapeFraction = 0f
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
    }

    private fun stopAnimator() {
        // remove listeners first so cancel() can't trigger the end callback
        currentAnimator?.apply { removeAllListeners(); cancel() }
        currentAnimator = null
    }

    private fun animateShape(
        to: Float, durationMs: Long, interp: TimeInterpolator, onEnd: (() -> Unit)? = null
    ) {
        stopAnimator()
        pillContainer?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        currentAnimator = ValueAnimator.ofFloat(shapeFraction, to).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener {
                shapeFraction = it.animatedValue as Float
                updatePillVisuals()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    pillContainer?.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
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

        // The pill grows out of (and shrinks back into) a 1px dot at the centre of the tap area
        val cx = (r.centerX() - margin).toFloat()
        val cy = (r.centerY() - top).toFloat()
        localCutout.set(cx - 0.5f, cy - 0.5f, cx + 0.5f, cy + 0.5f)
        localPill.set(0f, 0f, pillW.toFloat(), pillH.toFloat())

        getPillWindow(pillW, pillH, margin, top)

        val remaining = (1f - shapeFraction).coerceIn(0f, 1f)
        val d = (OPEN_MS * remaining).toLong().coerceAtLeast(120L)
        animateShape(1f, d, OvershootInterpolator(0.9f))
    }

    private fun hidePill(animate: Boolean = true) {
        if (!animate) { closePillImmediately(); return }
        handler.removeCallbacks(autoHide)
        if (!isPillVisible) return
        isPillVisible = false

        val d = (CLOSE_MS * shapeFraction.coerceIn(0f, 1f)).toLong().coerceAtLeast(120L)
        animateShape(0f, d, DecelerateInterpolator()) {
            if (!isPillVisible) removePillWindow()
        }
    }

    private fun closePillImmediately() {
        handler.removeCallbacks(autoHide)
        stopAnimator()
        isPillVisible = false
        shapeFraction = 0f
        removePillWindow()
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
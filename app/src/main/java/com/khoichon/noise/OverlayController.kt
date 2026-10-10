package com.khoichon.noise

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.min

/**
 * Owns the two overlay windows ("display over other apps"):
 *  - the TV: a small draggable window at the bottom centre, on screen for the whole round;
 *  - the takeover: a full-screen touch-swallowing window, only attached while Noise is actually
 *    out (so the phone is usable during the long emerge animation and after the round).
 * The TV is always kept above the takeover so it stays touchable.
 */
class OverlayController(private val context: Context, val state: SceneState) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    val scene = SceneView(context, state)
    val tv = TvView(context)

    private var takeoverShown = false
    private var tvShown = false
    private var tvLp: WindowManager.LayoutParams = newTvParams()
    private var dragStartX = 0
    private var lastFail = 0L

    /** After a failed addView (permission revoked?) don't retry on every game tick. */
    private fun recentlyFailed() = lastFail != 0L && SystemClock.uptimeMillis() - lastFail < 2000

    var listener: OverlayListener? = null
        set(value) { field = value; scene.listener = value }

    init {
        tv.onDragStart = { dragStartX = tvLp.x }
        tv.onDrag = { dx ->
            val (w, _) = screenSize()
            tvLp.x = (dragStartX + dx.toInt()).coerceIn(0, (w - tvLp.width).coerceAtLeast(0))
            if (tvShown) runCatching { wm.updateViewLayout(tv, tvLp) }
            listener?.onTvMoved()
        }
        tv.onTap = {
            // Tap the TV to feed it a file. Allowed from here because we hold a visible overlay.
            val i = Intent(context, FeedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(FeedActivity.EXTRA_FROM_APP, false)
            runCatching { context.startActivity(i) }
        }
    }

    // ---------------------------------------------------------------- geometry

    @Suppress("DEPRECATION")
    fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** Height of the system navigation bar, so the TV sits above it. */
    private fun navBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            if (ins.bottom > 0) return ins.bottom
        }
        return (24 * density).toInt()
    }

    /** Top / bottom keep-out for fire alarms (status bar and nav bar areas). */
    fun safeTop(): Float = 32f * density
    fun safeBottom(): Float = navBarHeight() + 16f * density

    private fun tvSize(): Pair<Int, Int> {
        val (w, h) = screenSize()
        val tw = min(150f * density, min(w, h) * 0.42f).toInt()
        return tw to (tw * 512f / 384f).toInt()
    }

    private fun newTvParams(): WindowManager.LayoutParams {
        val (w, h) = screenSize()
        val (tw, th) = tvSize()
        return baseParams(tw, th).apply {
            x = (w - tw) / 2
            y = h - th - navBarHeight()
        }
    }

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun takeoverParams(): WindowManager.LayoutParams {
        val (w, h) = screenSize()
        return baseParams(w, h).apply { x = 0; y = 0 }
    }

    /** The TV window's rectangle in screen coordinates. */
    fun tvRect(): RectF = RectF(
        tvLp.x.toFloat(), tvLp.y.toFloat(),
        (tvLp.x + tvLp.width).toFloat(), (tvLp.y + tvLp.height).toFloat()
    )

    // ---------------------------------------------------------------- windows

    fun showTv() {
        if (tvShown || recentlyFailed()) return
        tvLp = newTvParams() // centred at the bottom each round
        try {
            wm.addView(tv, tvLp)
            tvShown = true
        } catch (e: Exception) {
            lastFail = SystemClock.uptimeMillis()
            Log.w(TAG, "can't show TV (overlay permission?)", e)
        }
    }

    fun hideTv() {
        if (!tvShown) return
        runCatching { wm.removeView(tv) }
        tvShown = false
    }

    fun setTakeover(show: Boolean) {
        if (show == takeoverShown) return
        if (show) {
            if (recentlyFailed()) return
            try {
                wm.addView(scene, takeoverParams())
                takeoverShown = true
                if (tvShown) { // keep the TV above the takeover so it stays touchable
                    runCatching { wm.removeView(tv) }
                    runCatching { wm.addView(tv, tvLp) }
                }
            } catch (e: Exception) {
                lastFail = SystemClock.uptimeMillis()
                Log.w(TAG, "can't show takeover (overlay permission?)", e)
            }
        } else {
            runCatching { wm.removeView(scene) }
            takeoverShown = false
        }
    }

    /** Rotation / resolution change: resize both windows. */
    fun relayout() {
        val (w, h) = screenSize()
        val (tw, th) = tvSize()
        tvLp.width = tw
        tvLp.height = th
        tvLp.x = ((w - tw) / 2).coerceAtLeast(0)
        tvLp.y = h - th - navBarHeight()
        if (tvShown) runCatching { wm.updateViewLayout(tv, tvLp) }
        if (takeoverShown) runCatching { wm.updateViewLayout(scene, takeoverParams()) }
    }

    fun setTvMode(mode: String) = tv.setMode(mode)
    fun playFx(file: String) = scene.startFx(file)
    fun stopFx() = scene.stopFx()
    fun invalidateScene() { if (takeoverShown) scene.invalidate() }

    fun release() {
        setTakeover(false)
        hideTv()
        scene.release()
        tv.release()
    }

    private companion object {
        const val TAG = "NoiseOverlay"
    }
}

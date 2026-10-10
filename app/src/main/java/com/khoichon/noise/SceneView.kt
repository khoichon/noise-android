package com.khoichon.noise

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The screen takeover: a transparent full-screen overlay that draws Noise's cursor and the fire
 * alarms, and swallows touches (your finger is the "cursor" Noise mirrors). While a fullscreen
 * scene (jumpscare / fire alarm) plays it paints it over everything on black.
 */
class SceneView(context: Context, private val st: SceneState) : View(context) {
    var listener: OverlayListener? = null

    private val density = resources.displayMetrics.density
    private val sprites = HashMap<String, Drawable>()
    private var currentNoise: Drawable? = null
    private var currentNoiseName = ""
    private var fx: AnimatedImageDrawable? = null
    private val black = Paint().apply { color = Color.BLACK }
    private val alarmPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val alarmBitmap: Bitmap? by lazy {
        runCatching {
            context.assets.open("sprites/firealarm.png").use { BitmapFactory.decodeStream(it) }?.let {
                Bitmap.createScaledBitmap(it, dp(GameEngine.ALARM_W_DP).toInt(), dp(GameEngine.ALARM_H_DP).toInt(), true)
            }
        }.getOrNull()
    }

    private fun dp(v: Float) = v * density

    private fun sprite(name: String): Drawable? {
        sprites[name]?.let { return it }
        val d = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "sprites/$name"))
        }.getOrNull() ?: return null
        d.callback = this
        sprites[name] = d
        return d
    }

    fun startFx(file: String) {
        stopFx()
        val d = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "sprites/$file"))
        }.getOrNull() as? AnimatedImageDrawable ?: return
        d.callback = this
        d.repeatCount = 0 // play once
        fx = d
        d.start()
        invalidate()
    }

    fun stopFx() {
        fx?.let { it.stop(); it.callback = null }
        fx = null
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        who === fx || sprites.containsValue(who) || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val scene = fx
        if (scene != null) {
            canvas.drawRect(0f, 0f, w, h, black)
            val iw = scene.intrinsicWidth.toFloat().coerceAtLeast(1f)
            val ih = scene.intrinsicHeight.toFloat().coerceAtLeast(1f)
            val s = min(w / iw, h / ih) // fit: keeps the 16:9 scene undistorted on a tall phone
            canvas.save()
            canvas.translate((w - iw * s) / 2f, (h - ih * s) / 2f)
            canvas.scale(s, s)
            scene.setBounds(0, 0, iw.toInt(), ih.toInt())
            scene.draw(canvas)
            canvas.restore()
            return
        }

        val bmp = alarmBitmap
        if (bmp != null) {
            for (a in st.alarms) {
                if (!a.visible || a.opacity <= 0.01f) continue
                alarmPaint.alpha = (a.opacity * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bmp, a.x, a.y, alarmPaint)
            }
        }

        if (st.noiseVisible) {
            if (currentNoiseName != st.noiseAnim) {
                (currentNoise as? AnimatedImageDrawable)?.stop()
                currentNoiseName = st.noiseAnim
                currentNoise = sprite(st.noiseAnim)
                (currentNoise as? AnimatedImageDrawable)?.let {
                    it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    it.start()
                }
            }
            currentNoise?.let {
                val size = dp(GameEngine.NOISE_SPRITE_DP).toInt()
                val x = st.noiseX.toInt()
                val y = st.noiseY.toInt()
                it.setBounds(x, y, x + size, y + size)
                it.draw(canvas)
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> listener?.onTouchDown(e.x, e.y)
            MotionEvent.ACTION_MOVE -> listener?.onTouchMove(e.x, e.y)
        }
        return true // the takeover owns the screen while Noise is out
    }

    fun release() {
        stopFx()
        (currentNoise as? AnimatedImageDrawable)?.stop()
        sprites.clear()
        currentNoise = null
        currentNoiseName = ""
    }
}

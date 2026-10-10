package com.khoichon.noise

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * The TV popup. Layers like the original: the "off" TV stays underneath while the emerge clips
 * play over it; "on" / "hijack on" are full TV animations on their own. Drag it sideways; tap it
 * to feed it a file (the RANS0M drop).
 */
class TvView(context: Context) : FrameLayout(context) {
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val base = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val anim = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val density = resources.displayMetrics.density

    private var downRawX = 0f
    private var downTime = 0L
    private var dragged = false

    init {
        addView(base, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(anim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        runCatching {
            base.setImageDrawable(
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "sprites/tv_off.png")) { decoder, _, _ ->
                    decoder.setTargetSize(384, 512)
                }
            )
        }
        setMode("off")
    }

    fun setMode(mode: String) {
        var showBase = true
        var file: String? = null
        var loop = false
        when (mode) {
            "on" -> { showBase = false; file = "tv_on.webp"; loop = true }
            "onHijack" -> { showBase = false; file = "hijackTv.webp"; loop = true }
            "emerge" -> file = "emerge.webp"
            "reverse" -> file = "emerge_reverse.webp"
            "hijackEmerge" -> file = "hijackemerge.webp"
        }
        base.visibility = if (showBase) View.VISIBLE else View.INVISIBLE
        (anim.drawable as? AnimatedImageDrawable)?.stop()
        if (file == null) {
            anim.setImageDrawable(null)
            return
        }
        // a freshly decoded drawable always starts from its first frame
        val d: Drawable? = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, "sprites/$file"))
        }.getOrNull()
        anim.setImageDrawable(d)
        (d as? AnimatedImageDrawable)?.let {
            it.repeatCount = if (loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
            it.start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downTime = e.eventTime
                dragged = false
                onDragStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                if (abs(dx) > 8 * density) dragged = true
                if (dragged) onDrag?.invoke(dx) // horizontal only, like the original popup
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && e.eventTime - downTime < 500) onTap?.invoke()
            }
        }
        return true
    }

    fun release() {
        (anim.drawable as? AnimatedImageDrawable)?.stop()
        anim.setImageDrawable(null)
    }
}

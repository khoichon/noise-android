package com.khoichon.noise

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView

/**
 * The "Lock the screen" consequence for a normal (non-hijack) death.
 *
 * Overlay windows can't be drawn over the lock screen, but an activity flagged show-when-locked
 * can. So, while the jumpscare's PAUSE text is on screen, the service starts this (invisible)
 * activity; it locks the phone and then waits. When the phone is next switched on, it is shown
 * above the lock screen and plays the rest of the scene (the PLAY text and the fade-out, the
 * clip jumpscare_tail.webp), then finishes.
 */
class LockScreenActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView

    private var armed = true      // first resume: lock the phone
    private var screenWasOff = false
    private var playing = false
    private var tailDone = false
    private var reportedLocked = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenWasOff = true
                    if (playing) finishTail() // switched off again mid-tail
                }
                Intent.ACTION_USER_PRESENT -> finishTail() // unlocked: nothing left to show
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true) // may be shown above the lock screen
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP // cover + centre-crop, like the overlay
        }
        setContentView(image)
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (armed) {
            armed = false
            // Lock slightly after we're up so we are the top activity when the phone wakes again.
            handler.postDelayed({ lockNow() }, 150)
            // if the phone somehow never switched off, don't linger
            handler.postDelayed({ if (!screenWasOff && !tailDone) finishTail() }, 10_000)
        } else if (screenWasOff && !playing && !tailDone) {
            playTail()
        }
    }

    private fun lockNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            if (!reportedLocked) {
                reportedLocked = true
                NoiseService.current?.lockEvent(NoiseService.LOCK_EVENT_LOCKED)
            }
        } catch (e: Exception) {
            // couldn't lock: leave the engine to carry on with the normal overlay scene
            tailDone = true
            finish()
        }
    }

    private fun playTail() {
        val d: Drawable? = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, "sprites/jumpscare_tail.webp"))
        }.getOrNull()
        val anim = d as? AnimatedImageDrawable
        if (anim == null) { finishTail(); return }
        playing = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        image.setImageDrawable(anim)
        anim.repeatCount = 0
        anim.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) { finishTail() }
        })
        NoiseService.current?.lockEvent(NoiseService.LOCK_EVENT_TAIL_STARTED)
        anim.start()
    }

    private fun finishTail() {
        if (tailDone) return
        tailDone = true
        NoiseService.current?.lockEvent(NoiseService.LOCK_EVENT_TAIL_DONE)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        (image.drawable as? AnimatedImageDrawable)?.stop()
        if (!tailDone) {
            tailDone = true
            NoiseService.current?.lockEvent(NoiseService.LOCK_EVENT_TAIL_DONE)
        }
        super.onDestroy()
    }
}

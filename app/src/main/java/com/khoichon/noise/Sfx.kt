package com.khoichon.noise

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * One looping-capable sound (assets/sounds/<name>.ogg), mirroring the desktop port's Sound class:
 * play / playFrom / playLooping / pause / reset. The player is created lazily on first use and
 * prepared asynchronously; whatever was asked for before it is ready is applied once it is.
 * Main thread only.
 */
class Sfx(private val context: Context, private val name: String) {
    private var mp: MediaPlayer? = null
    private var ready = false
    private var want = 0        // 0 = silent, 1 = play once, 2 = loop
    private var startAtMs = 0
    private var dirty = false   // has played since the last reset()

    /** Length of the clip in ms once it has been prepared, else 0. */
    val durationMs: Int
        get() = if (ready) runCatching { mp?.duration ?: 0 }.getOrDefault(0) else 0

    /** Create and start preparing the player without playing anything. */
    fun preload() = ensure()

    private fun ensure() {
        if (mp != null) return
        val player = MediaPlayer()
        try {
            context.assets.openFd("sounds/$name.ogg").use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setOnPreparedListener { ready = true; apply() }
            player.setOnErrorListener { _, _, _ -> ready = false; true }
            player.prepareAsync()
            mp = player
        } catch (e: Exception) {
            runCatching { player.release() }
        }
    }

    private fun apply() {
        val m = mp ?: return
        if (!ready) return
        runCatching {
            when (want) {
                0 -> if (m.isPlaying) m.pause()
                1 -> { m.isLooping = false; m.seekTo(startAtMs); m.start() }
                else -> { m.isLooping = true; if (!m.isPlaying) m.start() }
            }
        }
    }

    fun play() { want = 1; startAtMs = 0; dirty = true; ensure(); apply() }

    fun playFrom(sec: Double) {
        want = 1; startAtMs = (sec * 1000).toInt().coerceAtLeast(0); dirty = true; ensure(); apply()
    }

    fun playLooping() {
        if (want == 2) return
        want = 2; dirty = true; ensure(); apply()
    }

    fun pause() {
        if (want == 0) return
        want = 0
        apply()
    }

    fun reset() {
        if (!dirty) return
        dirty = false
        if (ready) runCatching { mp?.seekTo(0) }
    }

    fun release() {
        want = 0
        ready = false
        runCatching { mp?.release() }
        mp = null
    }
}

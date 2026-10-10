package com.khoichon.noise

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Port of the desktop game brain (electron/game/game.js, itself a port of MainWindow.xaml.cs from
 * noisedoors v2 "HIJACK"): same constants, same chase / freeze / hijack logic, same fire alarm
 * rules as the multi-alarm desktop port. What changes on a phone:
 *  - your finger on the takeover overlay is the cursor (it stays where you last touched);
 *  - key presses become taps, and Noise echoes them back as a vibration;
 *  - distances are in dp; one screen, one TV.
 * Everything runs on the main thread (a [CoroutineScope] on Dispatchers.Main).
 */
class GameEngine(
    private val context: Context,
    private val cfg: Config,
    private val overlay: OverlayController,
    private val scope: CoroutineScope,
) : OverlayListener {

    companion object {
        const val ALARM_W_DP = 40f
        const val ALARM_H_DP = 59f
        const val NOISE_SPRITE_DP = 36f

        private const val CURSOR_HISTORY_DELAY_MS = 200L
        private const val THREAT_DELAY_MS = 400L
        private const val NOISE_IDLE_THRESHOLD_DP = 30f
        private const val DEATH_DISTANCE_DP = 32f
        private const val FIRE_ALARM_FADE_DISTANCE_DP = 100f
        private const val TICK_MS = 10L
        private const val EMERGE_MS = 8720L
        private const val HIJACK_EMERGE_MS = 13000L
        private const val JUMPSCARE_MS = 9840L
        private const val HIJACK_JUMPSCARE_LEAD_MS = 200L
        private const val DEATH_CONSEQUENCE_DELAY_MS = 5200L
        private const val FIRE_ALARM_FX_MS = 22000L
        private const val REVERSE_FALLBACK_MS = 59580L
        private const val REVERSE_SPEED_DP = 500f
        private const val REVERSE_MIN_MS = 1500L
    }

    /** Called ~5.2 s after the jumpscare starts (the service runs the opt-in consequences). */
    var onDeath: () -> Unit = {}

    val state: SceneState = overlay.state

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private class Pos(var time: Long, val x: Float, val y: Float)
    private class Tap(var time: Long)

    // ---- sounds (same files as the desktop port) ----
    private val tvStatic = Sfx(context, "Noisestatic")
    private val noiseIdle = Sfx(context, "Noiseidle")
    private val noiseThreat = Sfx(context, "Noise_threat")
    private val noiseEmergeS = Sfx(context, "Noiseemerge")
    private val noiseEmergeMusic = Sfx(context, "Noiseemerge_music")
    private val fireAlarmS = Sfx(context, "firealarm")
    private val fireAlarmPull = Sfx(context, "Fire_alarm_pull")
    private val noiseDeath1 = Sfx(context, "Noise_death_1")
    private val noiseDeathMusic = Sfx(context, "Noise_death_music")
    private val noisePause = Sfx(context, "Noisepause2")
    private val hijackIdle = Sfx(context, "Hijackidle")
    private val hijackEmergeS = Sfx(context, "hijackemerge")
    private val hijackPauseS = Sfx(context, "Hijackpause")
    private val hijackSong = Sfx(context, "Hijacksong")
    private val hijackJumpscareS = Sfx(context, "hijackJumpscare")
    private val reverseSound = Sfx(context, "Noisereverse")
    private val allSounds = listOf(
        tvStatic, noiseIdle, noiseThreat, noiseEmergeS, noiseEmergeMusic, fireAlarmS, fireAlarmPull,
        noiseDeath1, noiseDeathMusic, noisePause, hijackIdle, hijackEmergeS, hijackPauseS, hijackSong,
        hijackJumpscareS, reverseSound
    )

    private val haptics = Haptics(context) { cfg.haptics }

    // ---- game state (Global.cs + MainWindow fields) ----
    private var tvOn = false
    private var emerging = false
    private var isHijacked = false
    private var hijackPause = false
    private var reversing = false
    private var reversingFlight = false
    private var roundActive = false   // emerge started .. round fully cleaned up
    private var fxPlaying = false     // a fullscreen scene (jumpscare / fire alarm) is on
    private var tvMode = "off"

    private val cursorPositions = ArrayList<Pos>()
    private val pressedTaps = ArrayList<Tap>()
    private var tapDebounce = 0

    private var curX = 0f
    private var curY = 0f
    private var lastCurX = 0f
    private var lastCurY = 0f
    private var haveLast = false
    private var prevNoiseX = 0f
    private var prevNoiseY = 0f
    private var noiseX = 0f
    private var noiseY = 0f
    private var cursorAnim: String? = null // null = frozen on a pause sprite
    private var threatHaptics = false

    private var fireAlarmLeft = 0

    private var tickJob: Job? = null
    private var fxJob: Job? = null
    private var timeoutJob: Job? = null

    init {
        overlay.listener = this
    }

    val isBusy: Boolean get() = roundActive

    private fun now() = SystemClock.uptimeMillis()
    private fun rnd(lo: Int, hi: Int) = lo + Random.nextInt(max(1, hi - lo))
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun pauseSprite() = if (isHijacked) "pausehijack.png" else "pausecursor.png"
    private fun setNoise(x: Float, y: Float) { noiseX = x; noiseY = y }
    private fun setCursorAnimation(file: String) { cursorAnim = file }

    // ---------------------------------------------------------------- public API

    /** Call Noise out now (a no-op while a round is running). */
    fun spawn() { scope.launch { noiseEmerge() } }

    /** The RANS0M drop: hijack Noise (reversing it into the TV first if it is out). */
    fun hijack() { scope.launch { onHijackTrigger() } }

    fun onScreenChanged() {
        val (w, h) = overlay.screenSize()
        curX = curX.coerceIn(0f, w.toFloat())
        curY = curY.coerceIn(0f, h.toFloat())
        for (a in state.alarms) if (!a.pulled && !alarmOnScreen(a, w, h)) replaceAlarm(a)
    }

    fun release() {
        tickJob?.cancel(); fxJob?.cancel(); timeoutJob?.cancel()
        haptics.stopAll()
        allSounds.forEach { it.release() }
    }

    // ---------------------------------------------------------------- input (OverlayListener)

    override fun onTouchDown(x: Float, y: Float) {
        curX = x; curY = y
        if (!tvOn) return
        pressedTaps.add(Tap(now()))
        tapDebounce++
        // pull an alarm if the finger landed on a visible one (opacity is judged from the new spot)
        val slop = dp(8f)
        val aw = dp(ALARM_W_DP)
        val ah = dp(ALARM_H_DP)
        for (a in state.alarms) {
            if (a.pulled) continue
            if (alarmOpacity(a, x, y) < 0.05f) continue
            if (x >= a.x - slop && x < a.x + aw + slop && y >= a.y - slop && y < a.y + ah + slop) {
                pullAlarm(a)
                break
            }
        }
    }

    override fun onTouchMove(x: Float, y: Float) {
        // ignore sub-pixel jitter of a resting finger so "holding still" really freezes Noise
        if (abs(x - curX) + abs(y - curY) < dp(1.5f)) return
        curX = x; curY = y
    }

    override fun onTvMoved() {
        if (!tvOn) return
        // if the dragged TV now covers an un-pulled alarm, it moves out of the way
        val r = overlay.tvRect()
        val pad = dp(20f)
        val aw = dp(ALARM_W_DP)
        val ah = dp(ALARM_H_DP)
        for (a in state.alarms) {
            if (a.pulled) continue
            val covers = a.x + aw > r.left - pad && a.x < r.right + pad &&
                a.y + ah > r.top - pad && a.y < r.bottom + pad
            if (covers) replaceAlarm(a)
        }
    }

    // ---------------------------------------------------------------- fire alarms

    private fun alarmCenterDist(a: Alarm, cx: Float, cy: Float): Float =
        hypot(a.x + dp(ALARM_W_DP) / 2f - cx, a.y + dp(ALARM_H_DP) / 2f - cy)

    private fun alarmOpacity(a: Alarm, cx: Float, cy: Float): Float =
        (1f - alarmCenterDist(a, cx, cy) / dp(FIRE_ALARM_FADE_DISTANCE_DP)).coerceIn(0f, 1f)

    private fun alarmOnScreen(a: Alarm, w: Int, h: Int) =
        a.x >= 0 && a.y >= 0 && a.x + dp(ALARM_W_DP) <= w && a.y + dp(ALARM_H_DP) <= h

    /** A random spot clear of the TV and of the other alarms; re-rolled a few times. */
    private fun randomAlarmPos(placed: List<Alarm>): Pair<Float, Float> {
        val (w, h) = overlay.screenSize()
        val aw = dp(ALARM_W_DP)
        val ah = dp(ALARM_H_DP)
        val side = dp(8f)
        val top = overlay.safeTop()
        val bottom = overlay.safeBottom()
        val maxX = max(1f, w - aw - side * 2)
        val maxY = max(1f, h - ah - top - bottom)
        val tv = overlay.tvRect()
        val pad = dp(20f)
        var x = side
        var y = top
        for (i in 0 until 25) {
            x = side + Random.nextFloat() * maxX
            y = top + Random.nextFloat() * maxY
            val behindTv = x + aw > tv.left - pad && x < tv.right + pad &&
                y + ah > tv.top - pad && y < tv.bottom + pad
            val stacked = placed.any { abs(it.x - x) < aw + dp(40f) && abs(it.y - y) < ah + dp(40f) }
            if (!behindTv && !stacked) break
        }
        return x to y
    }

    /** The whole set of FireAlarmAmount alarms; all show at once, a pulled one stays gone. */
    private fun spawnAlarms() {
        val n = cfg.fireAlarmAmount
        state.alarms.clear()
        for (i in 0 until n) {
            val (x, y) = randomAlarmPos(state.alarms)
            state.alarms.add(Alarm(i, x, y))
        }
        fireAlarmLeft = state.alarms.size
    }

    private fun replaceAlarm(a: Alarm) {
        if (a.pulled) return
        val (x, y) = randomAlarmPos(state.alarms.filter { it !== a && !it.pulled })
        a.x = x
        a.y = y
    }

    private fun pullAlarm(a: Alarm) {
        if (!tvOn || a.pulled) return
        a.pulled = true // pulled alarms disappear and never come back this round
        a.visible = false
        fireAlarmPull.play()
        haptics.pull()
        fireAlarmLeft--
        if (fireAlarmLeft <= 0) {
            tvOn = false
            isHijacked = false
            fireAlarmS.play()
            playFx("firealarm.webp", FIRE_ALARM_FX_MS)
        }
    }

    // ---------------------------------------------------------------- round plumbing

    private fun setTvMode(mode: String) {
        tvMode = mode
        overlay.setTvMode(mode)
    }

    private fun silenceAll() {
        tvStatic.pause(); noiseIdle.pause(); noiseThreat.pause(); hijackIdle.pause()
        hijackSong.reset(); hijackSong.pause()
        setThreatHaptics(false)
    }

    private fun setThreatHaptics(on: Boolean) {
        if (on == threatHaptics) return
        threatHaptics = on
        haptics.threat(on)
    }

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var last = now()
            while (isActive) {
                val t = now()
                tick(t, (t - last).coerceIn(0L, 50L))
                last = t
                delay(TICK_MS)
            }
        }
    }

    /** Keep the overlay windows matching the game state. */
    private fun syncOverlay() {
        overlay.setTakeover(tvOn || reversingFlight || fxPlaying)
        if (roundActive && !fxPlaying) overlay.showTv() else overlay.hideTv()
    }

    private fun playFx(file: String, ms: Long) {
        fxJob?.cancel()
        fxPlaying = true
        syncOverlay()
        overlay.playFx(file)
        fxJob = scope.launch {
            delay(ms)
            endRound()
        }
    }

    /** Everything off again: no TV, no takeover, the phone is yours. */
    private fun endRound() {
        timeoutJob?.cancel()
        fxJob?.cancel()
        tvOn = false
        isHijacked = false
        fxPlaying = false
        roundActive = false
        overlay.stopFx()
        state.alarms.clear()
        state.noiseVisible = false
        cursorAnim = null
        setTvMode("off")
        silenceAll()
        haptics.stopAll()
        syncOverlay()
        tickJob?.cancel()
    }

    private fun startTimeout() {
        timeoutJob?.cancel()
        val s = cfg.roundTimeoutSec
        if (s <= 0) return
        timeoutJob = scope.launch {
            delay(s * 1000L)
            if (tvOn) endRound() // Noise loses interest
        }
    }

    // ---------------------------------------------------------------- emerge

    private suspend fun noiseEmerge() {
        if (tvOn || emerging || reversing || fxPlaying) return
        emerging = true
        roundActive = true
        cursorPositions.clear()
        pressedTaps.clear()
        tapDebounce = 0
        reverseSound.preload() // so its real length is known if the TV gets fed mid-round

        // the finger starts "at the middle of the screen" until you touch it
        val (sw, sh) = overlay.screenSize()
        curX = sw / 2f; curY = sh * 0.45f
        lastCurX = curX; lastCurY = curY; haveLast = true

        overlay.showTv()
        ensureTicking()

        val wasHijackBefore = isHijacked
        val wait: Long
        if (isHijacked) {
            wait = HIJACK_EMERGE_MS
            setTvMode("hijackEmerge")
            hijackEmergeS.play()
        } else {
            wait = EMERGE_MS
            setTvMode("emerge")
            noiseEmergeS.play()
            noiseEmergeMusic.play()
        }

        delay(wait)

        if (isHijacked && !wasHijackBefore) {
            // hijacked by a drop mid-emerge: restart the emerge as the hijack one (v2 behavior)
            emerging = false
            noiseEmerge()
            return
        }
        if (!roundActive) { emerging = false; return } // service was told to stop meanwhile

        val tv = overlay.tvRect()
        setNoise(tv.left + tv.width() * 120f / 232f, tv.top + tv.height() * 250f / 310f)

        spawnAlarms()

        val t = now()
        val tx = curX
        val ty = curY
        for (i in 1 until 20) {
            cursorPositions.add(Pos(t - 10L * (20 - i), lerp(noiseX, tx, i / 20f), lerp(noiseY, ty, i / 20f)))
        }

        tvOn = true
        emerging = false
        broadcastNoise()
        syncOverlay()
        startTimeout()
    }

    // ---------------------------------------------------------------- per tick

    private fun broadcastNoise() {
        val visible = tvOn || reversingFlight
        state.noiseVisible = visible
        state.noiseX = noiseX
        state.noiseY = noiseY
        state.noiseAnim = cursorAnim ?: pauseSprite()
    }

    private fun tick(now: Long, dt: Long) {
        val cx = curX
        val cy = curY
        val moved = !haveLast || cx != lastCurX || cy != lastCurY
        handleDeathCheck(cx, cy)
        updateTvAndAlarms(cx, cy)
        handleInputAndCursor(cx, cy, moved, now, dt)
        prevNoiseX = noiseX; prevNoiseY = noiseY
        lastCurX = cx; lastCurY = cy; haveLast = true
        broadcastNoise()
        syncOverlay()
        overlay.invalidateScene()
    }

    private fun handleDeathCheck(cx: Float, cy: Float) {
        if (!tvOn) return
        val distance = abs(noiseX - cx) + abs(noiseY - cy)
        if (distance >= dp(DEATH_DISTANCE_DP)) return
        die()
    }

    private fun die() {
        tvOn = false
        haptics.jumpscare()
        if (isHijacked) {
            playFx("hijackJumpscare.webp", JUMPSCARE_MS)
            hijackJumpscareS.play()
            scope.launch {
                delay(HIJACK_JUMPSCARE_LEAD_MS)
                isHijacked = false
                delay(DEATH_CONSEQUENCE_DELAY_MS)
                onDeath()
            }
        } else {
            playFx("jumpscare.webp", JUMPSCARE_MS)
            noiseDeath1.play()
            noiseDeathMusic.play()
            isHijacked = false
            scope.launch {
                delay(DEATH_CONSEQUENCE_DELAY_MS)
                onDeath() // 5.2 s after the jumpscare, like v2
            }
        }
    }

    private fun updateTvAndAlarms(cx: Float, cy: Float) {
        if (tvOn) {
            for (a in state.alarms) {
                if (a.pulled) { a.visible = false; continue } // pulled ones are gone and stay gone
                a.opacity = alarmOpacity(a, cx, cy)
                a.visible = true
            }
            val desired = if (isHijacked) "onHijack" else "on"
            if (tvMode != desired) {
                setTvMode(desired)
                tvStatic.playLooping()
            }
        } else {
            for (a in state.alarms) a.visible = false
            if (!emerging && !reversing && tvMode != "off") {
                setTvMode("off")
                tvStatic.pause()
            }
        }
    }

    private fun handleInputAndCursor(cx: Float, cy: Float, moved: Boolean, now: Long, dt: Long) {
        var debounced = true
        val actualSpeed = if (isHijacked) cfg.noiseSpeed * cfg.hijackSpeed else cfg.noiseSpeed.toFloat()
        val speed = max(0.01f, actualSpeed)

        if (cursorPositions.size > 2) {
            val youngest = cursorPositions[cursorPositions.size - 1]
            var storeDebounce = 10.0
            if (cursorPositions.size > 100.0 / speed) storeDebounce = 200.0 * speed
            if (now < youngest.time + storeDebounce) debounced = false
        }

        if (moved && tvOn && debounced) cursorPositions.add(Pos(now, cx, cy))

        if (isHijacked && tvOn) {
            hijackSong.playLooping()
        } else {
            hijackSong.reset()
            hijackSong.pause()
        }

        val active = ((!isHijacked && (moved || tapDebounce > 0)) || (isHijacked && !hijackPause)) && tvOn

        if (!active) {
            noiseIdle.pause()
            noiseThreat.pause()
            hijackIdle.pause()
            setThreatHaptics(false)

            if (!reversing && cursorAnim != null) {
                cursorAnim = null
                if (isHijacked) hijackPauseS.play() else noisePause.play()
            }

            // freeze: push the whole history forward in time so Noise stands still while you do
            for (s in cursorPositions) s.time += dt
            for (k in pressedTaps) k.time += dt
            return
        }

        if (isHijacked && Random.nextInt(500) <= 1) {
            // v2's random micro-pause: the hijacked Noise stutters for 100-1000 ms now and then
            hijackPause = true
            scope.launch {
                delay(rnd(100, 1000).toLong())
                hijackPause = false
            }
        }

        if (tapDebounce > 0) tapDebounce--

        val nextX: Float
        val nextY: Float
        val oldestTime: Long
        if (cursorPositions.isNotEmpty()) {
            val oldest = cursorPositions[0]
            oldestTime = oldest.time
            nextX = oldest.x
            nextY = oldest.y

            var usedDelay = CURSOR_HISTORY_DELAY_MS.toDouble()
            if (isHijacked) usedDelay /= 2.0
            if (cursorPositions.size > 400.0 / speed) usedDelay /= 4.0 / speed

            if (now - oldest.time > usedDelay / speed) {
                setNoise(nextX, nextY)
                cursorPositions.removeAt(0)
            }
        } else {
            oldestTime = now
            nextX = cx
            nextY = cy
        }

        if (pressedTaps.isNotEmpty()) {
            val k = pressedTaps[0]
            if (now - k.time > CURSOR_HISTORY_DELAY_MS / speed) {
                haptics.tap() // Noise "echoes" your taps, delayed like the original echoed keys
                pressedTaps.removeAt(0)
            }
        }

        val magnitude = abs(prevNoiseX - nextX) + abs(prevNoiseY - nextY)
        if (magnitude < dp(NOISE_IDLE_THRESHOLD_DP)) {
            if (isHijacked) {
                setCursorAnimation("hijackcursor.gif")
                noiseIdle.pause()
                hijackIdle.playLooping()
                noiseThreat.pause()
            } else {
                noiseIdle.playLooping()
                hijackIdle.pause()
                noiseThreat.pause()
                setCursorAnimation("cursor.gif")
            }
            setThreatHaptics(false)
        } else if (now - oldestTime > THREAT_DELAY_MS) {
            noiseIdle.pause()
            hijackIdle.pause()
            noiseThreat.playLooping()
            setCursorAnimation(if (isHijacked) "ffhijack.gif" else "ffcursor.gif")
            setThreatHaptics(true)
        }
    }

    // ---------------------------------------------------------------- hijack (FileDropInTv)

    /**
     * The reversal (a port addition of the desktop version): Noise flies in a straight line back
     * to the TV's spawn point on its normal cursor and vanishes there; the TV then plays the
     * emerge clip backwards while Noisereverse.ogg plays, ending with the clip, which hands over
     * to the hijack intro.
     */
    private suspend fun reverseNoiseIntoTv() {
        if (reversing) return
        reversing = true
        noiseIdle.pause(); noiseThreat.pause(); hijackIdle.pause()
        hijackSong.reset(); hijackSong.pause()
        setThreatHaptics(false)

        val trackMs = if (reverseSound.durationMs > 0) reverseSound.durationMs.toLong() else REVERSE_FALLBACK_MS

        val tv = overlay.tvRect()
        val goalX = tv.left + tv.width() * 120f / 232f
        val goalY = tv.top + tv.height() * 250f / 310f
        val best = hypot(goalX - noiseX, goalY - noiseY)

        // budget: the flight plus the reversed emerge must fit inside the track
        val allowedFlightMs = max(REVERSE_MIN_MS, trackMs - EMERGE_MS - 50)
        var duration = max(REVERSE_MIN_MS, (best / dp(REVERSE_SPEED_DP) * 1000f).toLong())
        var songStartSec = 0.0
        if (duration > allowedFlightMs) {
            duration = allowedFlightMs // too far for the pace: fly faster, still home in time
        } else {
            songStartSec = max(0.0, (trackMs - duration - EMERGE_MS - 50) / 1000.0)
        }

        reversingFlight = true
        cursorAnim = "cursor.gif" // the normal cursor while flying home
        reverseSound.playFrom(songStartSec)
        broadcastNoise()
        syncOverlay()

        val fromX = noiseX
        val fromY = noiseY
        val startedAt = now()
        while (true) {
            val f = min(1f, (now() - startedAt).toFloat() / duration)
            setNoise(lerp(fromX, goalX, f), lerp(fromY, goalY, f))
            broadcastNoise()
            if (f >= 1f) break
            delay(TICK_MS)
        }
        setNoise(goalX, goalY)

        // Noise is at the TV: disappear, then let the reversed emerge play out its full clip
        reversingFlight = false
        cursorAnim = null
        state.noiseVisible = false
        setTvMode("reverse")
        tvStatic.pause()
        syncOverlay()
        delay(EMERGE_MS)
        reversing = false
    }

    private suspend fun onHijackTrigger() {
        if (isHijacked || fxPlaying) return // v2: drops are ignored while already hijacked
        val wasActive = tvOn
        tvOn = false
        isHijacked = true
        if (emerging) return // the running emerge notices the flag and restarts as hijack (v2)
        if (wasActive) reverseNoiseIntoTv()
        noiseEmerge()
    }
}

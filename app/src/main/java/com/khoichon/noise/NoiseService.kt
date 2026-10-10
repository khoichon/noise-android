package com.khoichon.noise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.app.KeyguardManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The always-on part: a foreground service that owns the overlay windows and the game, and runs
 * the spawn timer (the desktop port's tray app). Noise only ever shows up while this runs.
 */
class NoiseService : Service() {
    companion object {
        const val ACTION_START = "com.khoichon.noise.START"
        const val ACTION_STOP = "com.khoichon.noise.STOP"
        const val ACTION_SPAWN = "com.khoichon.noise.SPAWN"
        const val ACTION_HIJACK = "com.khoichon.noise.HIJACK"
        const val ACTION_CONFIG_CHANGED = "com.khoichon.noise.CONFIG_CHANGED"

        private const val CHANNEL_ID = "noise"
        private const val NOTIF_ID = 1

        const val LOCK_EVENT_LOCKED = 1
        const val LOCK_EVENT_TAIL_STARTED = 2
        const val LOCK_EVENT_TAIL_DONE = 3

        @Volatile
        var running = false
            private set

        /** The live service (same process as the activities), so [LockScreenActivity] can report back. */
        @Volatile
        var current: NoiseService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var cfg: Config
    private lateinit var overlay: OverlayController
    private lateinit var engine: GameEngine
    private var timerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        current = this
        cfg = Config(this)
        overlay = OverlayController(this, SceneState())
        engine = GameEngine(this, cfg, overlay, scope)
        engine.onDeath = { runDeathConsequences() }
        engine.lockAvailable = { adminActive() }
        engine.onLockPrepare = { startLockScreenActivity() }
        engine.onLockNow = { lockPhone() }
        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), foregroundType())
        restartTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPAWN -> {
                if (Settings.canDrawOverlays(this)) {
                    restartTimer() // a manual call restarts the timer, like the original
                    engine.spawn()
                }
            }
            ACTION_HIJACK -> engine.hijack()
            ACTION_CONFIG_CHANGED -> restartTimer()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.relayout()
        engine.onScreenChanged()
    }

    override fun onDestroy() {
        running = false
        current = null
        timerJob?.cancel()
        engine.release()
        overlay.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- spawn timer

    private fun restartTimer() {
        timerJob?.cancel()
        if (!cfg.spawnAutomatically) return
        timerJob = scope.launch {
            while (isActive) {
                val (lo, hi) = cfg.spawnRange()
                delay((lo + Random.nextInt(hi - lo + 1)) * 1000L)
                while (isActive && !canSpawnNow()) delay(3000) // not while the screen is off / locked
                engine.spawn()
            }
        }
    }

    private fun canSpawnNow(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    // ---------------------------------------------------------------- death consequences

    private fun adminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, NoiseAdmin::class.java))
    }

    private fun lockPhone() {
        runCatching {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (adminActive()) dpm.lockNow()
        }
    }

    /** Jumpscare, mid-PAUSE: bring up the activity that locks the phone and waits to play the tail. */
    private fun startLockScreenActivity() {
        val i = Intent(this, LockScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // allowed from here: our overlay is on screen. If it still fails the engine falls back to
        // a plain lock when the jumpscare ends.
        runCatching { startActivity(i) }
    }

    /** From [LockScreenActivity] (main thread): the phone got locked / the tail started / ended. */
    fun lockEvent(kind: Int) {
        when (kind) {
            LOCK_EVENT_LOCKED -> engine.onDeathLocked()
            LOCK_EVENT_TAIL_STARTED -> engine.onDeathTailStarted()
            LOCK_EVENT_TAIL_DONE -> engine.onDeathTailDone()
        }
    }

    private fun runDeathConsequences() {
        if (cfg.testMode) return // test mode never applies them
        when (cfg.deathAction) {
            DeathAction.NONE -> Unit
            DeathAction.LOCK -> Unit // handled by the jumpscare-synced lock plan in the engine
            DeathAction.HOME -> runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "NOISE", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while NOISE is lurking"
                setShowBadge(false)
            }
        )
    }

    private fun servicePi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, NoiseService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_noise)
            .setContentTitle("NOISE is lurking")
            .setContentText("Stop it here if it ever gets too much.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Call Noise", servicePi(ACTION_SPAWN, 1))
            .addAction(0, "Stop", servicePi(ACTION_STOP, 2))
            .build()
    }
}

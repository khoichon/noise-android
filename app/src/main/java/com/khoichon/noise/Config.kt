package com.khoichon.noise

import android.content.Context
import android.content.SharedPreferences

/** What happens after Noise catches you (opt-in; skipped entirely in test mode). */
enum class DeathAction { NONE, HOME, LOCK }

/**
 * Same options as the original's Config.cs / the desktop port, minus the ones that cannot exist
 * on Android (shutdown / custom shell command / multi-screen), plus a few phone-specific ones.
 * Values are clamped on read, so a bad number can never reach the game.
 */
class Config(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("noiseconfig", Context.MODE_PRIVATE)

    var spawnAutomatically: Boolean
        get() = p.getBoolean("SpawnAutomatically", true)
        set(v) = p.edit().putBoolean("SpawnAutomatically", v).apply()

    var minSpawnDelay: Int
        get() = p.getInt("MinSpawnDelay", 5).coerceIn(0, 86400)
        set(v) = p.edit().putInt("MinSpawnDelay", v.coerceIn(0, 86400)).apply()

    var maxSpawnDelay: Int
        get() = p.getInt("MaxSpawnDelay", 600).coerceIn(0, 86400)
        set(v) = p.edit().putInt("MaxSpawnDelay", v.coerceIn(0, 86400)).apply()

    /** 1-100; scales how fast Noise replays your movement (v2). */
    var noiseSpeed: Int
        get() = p.getInt("NoiseSpeed", 1).coerceIn(1, 100)
        set(v) = p.edit().putInt("NoiseSpeed", v.coerceIn(1, 100)).apply()

    /** 0.1-100, as a multiple of [noiseSpeed] (0.5 = the original's half). */
    var hijackSpeed: Float
        get() = p.getFloat("HijackSpeed", 0.5f).coerceIn(0.1f, 100f)
        set(v) = p.edit().putFloat("HijackSpeed", v.coerceIn(0.1f, 100f)).apply()

    /** 1-100 alarms; all of them show at once and every one has to be pulled. */
    var fireAlarmAmount: Int
        get() = p.getInt("FireAlarmAmount", 1).coerceIn(1, 100)
        set(v) = p.edit().putInt("FireAlarmAmount", v.coerceIn(1, 100)).apply()

    /** Phone addition: Noise gives up after this many seconds (0 = never). */
    var roundTimeoutSec: Int
        get() = p.getInt("RoundTimeoutSec", 120).coerceIn(0, 3600)
        set(v) = p.edit().putInt("RoundTimeoutSec", v.coerceIn(0, 3600)).apply()

    /** Phone addition: vibration while Noise is hunting, on the jumpscare, and for key echoes. */
    var haptics: Boolean
        get() = p.getBoolean("Haptics", true)
        set(v) = p.edit().putBoolean("Haptics", v).apply()

    var deathAction: DeathAction
        get() = runCatching { DeathAction.valueOf(p.getString("DeathAction", "NONE") ?: "NONE") }
            .getOrDefault(DeathAction.NONE)
        set(v) = p.edit().putString("DeathAction", v.name).apply()

    /** Skips the death consequences. */
    var testMode: Boolean
        get() = p.getBoolean("TestMode", false)
        set(v) = p.edit().putBoolean("TestMode", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("StartOnBoot", false)
        set(v) = p.edit().putBoolean("StartOnBoot", v).apply()

    /** Min/max in seconds with max never below min. */
    fun spawnRange(): Pair<Int, Int> {
        val lo = minSpawnDelay
        return lo to maxSpawnDelay.coerceAtLeast(lo)
    }
}

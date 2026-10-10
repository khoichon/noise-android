package com.khoichon.noise

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/**
 * The "drop RANS0M on the TV" check, same as the original's .exe hash check: the SHA-256 of the
 * file has to be one of the known RANS0M builds (assets/ransom-hashes.txt). Anything else is
 * ignored.
 */
object RansomHashes {
    @Volatile private var cache: Set<String>? = null

    fun known(context: Context): Set<String> {
        cache?.let { return it }
        val set = HashSet<String>()
        runCatching {
            context.assets.open("ransom-hashes.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val h = raw.substringBefore('#').trim().lowercase()
                    if (h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' }) set.add(h)
                }
            }
        }
        cache = set
        return set
    }

    /** SHA-256 (lowercase hex) of the file behind [uri], or null if it can't be read. */
    fun sha256(context: Context, uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun matches(context: Context, uri: Uri): Boolean {
        val h = sha256(context, uri) ?: return false
        return h in known(context)
    }
}

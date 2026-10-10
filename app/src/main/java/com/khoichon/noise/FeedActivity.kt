package com.khoichon.noise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat

/**
 * "Drag the RANS0M executable into the TV", the phone way: pick a file (or share one to this app),
 * and if its SHA-256 matches a known RANS0M build the running NOISE gets hijacked. Anything else
 * is ignored, like the original's .exe check.
 */
class FeedActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FROM_APP = "from_app"
    }

    private var fromApp = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) feed(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromApp = intent.getBooleanExtra(EXTRA_FROM_APP, false)
        val shared: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent.data
        }
        if (shared != null) feed(shared) else if (savedInstanceState == null) picker.launch(arrayOf("*/*"))
    }

    private fun feed(uri: Uri) {
        val app = applicationContext
        Thread {
            val ok = RansomHashes.matches(app, uri)
            runOnUiThread {
                if (ok && NoiseService.running) {
                    startService(Intent(app, NoiseService::class.java).setAction(NoiseService.ACTION_HIJACK))
                } else if (ok) {
                    Toast.makeText(app, "Start NOISE first.", Toast.LENGTH_SHORT).show()
                } else if (fromApp) {
                    Toast.makeText(app, "The TV doesn't want that.", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }.start()
    }
}

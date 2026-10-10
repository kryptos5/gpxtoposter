package com.gpxtoposter.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.gpxtoposter.app.ui.App
import com.gpxtoposter.app.ui.GpxPosterTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            GpxPosterTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Accept track files shared ("Share to…") or opened ("Open with…") from other apps. */
    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        viewModel.loadTrack(uri)
    }
}

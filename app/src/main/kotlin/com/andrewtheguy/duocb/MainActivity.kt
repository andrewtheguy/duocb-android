package com.andrewtheguy.duocb

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.andrewtheguy.duocb.ui.DuocbRoot
import com.andrewtheguy.duocb.ui.DuocbTheme

class MainActivity : ComponentActivity() {
    private val controller: SessionController get() = DuocbApplication.controller(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DuocbTheme {
                DuocbRoot(controller)
            }
        }
        // Debug builds only (a no-op in release, see the two DebugAutostart
        // source sets): the E2E hook that sets up an identity and starts a
        // session from the launch intent's extras.
        DebugAutostart.apply(controller, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugAutostart.apply(controller, intent)
    }

    /**
     * Back in the foreground: catch up on events at once and notice a runtime
     * that died while the process sat in the background.
     */
    override fun onResume() {
        super.onResume()
        controller.noteForegrounded()
    }
}

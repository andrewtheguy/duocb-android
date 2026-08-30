package com.andrewtheguy.duocb

import android.app.Application
import android.content.Context

/**
 * Process-scoped owner of the one [SessionController]. It lives here rather
 * than in the activity because a running session must survive the activity's
 * recreation (rotation, the system trimming memory while the app is in the
 * foreground), and because `DuocbNative.init` has to run once before any other
 * native call.
 */
class DuocbApplication : Application() {
    lateinit var controller: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        DuocbNative.init(this)
        controller = SessionController(this)
    }

    companion object {
        fun controller(context: Context): SessionController =
            (context.applicationContext as DuocbApplication).controller
    }
}

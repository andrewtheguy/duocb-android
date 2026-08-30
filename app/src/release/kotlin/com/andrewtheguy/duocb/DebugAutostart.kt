package com.andrewtheguy.duocb

import android.content.Intent

/**
 * Release builds: no E2E hook. The debug source set's copy of this object is
 * the real one; keeping this a no-op (rather than a flag) means the shipping
 * APK contains no code path that trusts a card without the pairing-code check.
 */
object DebugAutostart {
    @Suppress("UNUSED_PARAMETER")
    fun apply(controller: SessionController, intent: Intent?) {}
}

package com.andrewtheguy.duocb

import android.content.Context

/**
 * The JNI surface of libduocb.so (duocb `crates/duocb-ffi/src/android.rs`).
 * The symbol names on the Rust side are bound to exactly this class, so it
 * must stay `com.andrewtheguy.duocb.DuocbNative` whatever the applicationId is.
 *
 * Everything here is a one-to-one image of the C API in duocb's `ios/duocb.h`,
 * whose comments document the config JSON, the event JSON and the `card_info`
 * shape. Conventions: pure helpers return `null` where C returns `-1`
 * (invalid input); `validate*` return `null` for valid and the reason
 * otherwise; handles are `Long`s with `0` as null.
 *
 * Lifecycle, one session at a time:
 *  1. [start] — returns a handle, or `0` with the error in `out[0]`.
 *  2. [nextEvent] — poll on a timer until it returns `null`.
 *  3. [stop] — exactly once per successful start; blocks up to five seconds,
 *     so call it off the main thread. The handle is dead after.
 */
object DuocbNative {
    init {
        System.loadLibrary("duocb")
    }

    /**
     * One-time process setup: logcat logging (tag `duocb`) and the JVM/context
     * registration iroh's Android DNS and interface discovery need. Call from
     * `Application.onCreate` before any other entry point. Idempotent.
     */
    @JvmStatic
    external fun init(context: Context)

    // Identity, card and name helpers — pure: no network, no storage.

    @JvmStatic
    external fun generateIdentity(): String

    @JvmStatic
    external fun generateSuffix(): String

    @JvmStatic
    external fun generateIrohSecret(): String

    /** `null` if valid, else the reason. */
    @JvmStatic
    external fun validateIdentity(nsec: String): String?

    @JvmStatic
    external fun identityPublicKey(nsec: String): String?

    @JvmStatic
    external fun identityFingerprint(nsec: String): String?

    /** `null` if valid, else the reason. */
    @JvmStatic
    external fun validateName(name: String): String?

    @JvmStatic
    external fun displayIdentity(name: String, suffix: String): String

    @JvmStatic
    external fun createIdentityCard(nsec: String, name: String, suffix: String): String?

    /** `null` if well formed and correctly signed, else the reason. Clock-free. */
    @JvmStatic
    external fun validateIdentityCard(card: String): String?

    /** The `card_info` JSON object, or `null` for a card that does not verify. */
    @JvmStatic
    external fun identityCardInfo(card: String): String?

    @JvmStatic
    external fun pairingCode(cardA: String, cardB: String): String?

    /** 1 = this device hosts, 0 = this device dials, -1 = invalid input. */
    @JvmStatic
    external fun sessionRole(selfCard: String, peerCard: String): Int

    // Card-setup PIN entry.

    @JvmStatic
    external fun normalizePin(pin: String): String?

    @JvmStatic
    external fun formatPin(pin: String): String

    @JvmStatic
    external fun sanitizePinChars(input: String): String

    /** `{"first","second"}` */
    @JvmStatic
    external fun splitPinGroups(first: String, second: String): String

    /** `{"entered","total","group"}` */
    @JvmStatic
    external fun pinProgress(input: String): String

    // Manual host-IP entry.

    /** `{"prefix","placeholder","hint","label"}` */
    @JvmStatic
    external fun joinIpContext(): String

    /** `{"outcome":"in_range"|"out_of_range"|"empty"|"malformed","ip"?}` */
    @JvmStatic
    external fun resolveJoinIp(entry: String): String

    // Session lifecycle.

    @JvmStatic
    external fun start(configJson: String, out: Array<String?>): Long

    @JvmStatic
    external fun nextEvent(handle: Long): String?

    @JvmStatic
    external fun sendClipboard(handle: Long, text: String): Int

    @JvmStatic
    external fun queryConnPath(handle: Long): Int

    @JvmStatic
    external fun refreshPin(handle: Long): Int

    @JvmStatic
    external fun disconnect(handle: Long): Int

    /** 1 = runtime alive, 0 = runtime ended, -1 = null handle. */
    @JvmStatic
    external fun isRunning(handle: Long): Int

    /** 0 = requested, -1 = null handle, -2 = runtime unavailable. */
    @JvmStatic
    external fun reconnect(handle: Long): Int

    /** Blocks up to five seconds; the handle must not be used afterwards. */
    @JvmStatic
    external fun stop(handle: Long)
}

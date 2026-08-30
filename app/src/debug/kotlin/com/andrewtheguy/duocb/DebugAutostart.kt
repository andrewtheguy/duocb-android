package com.andrewtheguy.duocb

import android.content.Intent
import android.util.Log

/**
 * E2E-test hook, **debug builds only** — the release source set replaces this
 * object with a no-op, so the APK that ships does not contain it. Sets up the
 * identity and starts a session straight from the launch intent's extras, so a
 * harness can drive a pairing without UI automation:
 *
 * ```
 * adb shell am start -n com.andrewtheguy.duocb/.MainActivity \
 *     --es duocb.autostart.role card_host --es duocb.autostart.name pixel
 * ```
 *
 * | extra (`duocb.autostart.` + …) | meaning |
 * | --- | --- |
 * | `nsec` | this device's private key; omitted mints a fresh one |
 * | `name` | short device name, default "phone" |
 * | `role` | `connect` \| `card_host` \| `card_join`; omit to stop at the hub |
 * | `peer` | `connect` only: the trusted peer's hex public key |
 * | `pin` | `card_join` only |
 * | `ip` | `card_join` only: the host's LAN IP for the side channel |
 * | `channel` | `lan_then_nostr` \| `lan_only` \| `nostr_only` |
 * | `trust_incoming` | `1` to import a traded card without the pairing-code screen |
 * | `peer_card` | a card to trust up front, so `connect` can run unattended |
 * | `send` | text to send once connected |
 *
 * `trust_incoming` deliberately bypasses the human check that card setup
 * exists for. That is only acceptable because it cannot exist in a shipping
 * build — never move this file out of the debug source set.
 */
object DebugAutostart {
    private const val PREFIX = "duocb.autostart."
    private const val TAG = "duocb"

    private class Hooks(override val autoTrustIncoming: Boolean, private var autosend: String?) :
        SessionController.TestHooks {
        override fun takeAutosend(): String? = autosend.also { autosend = null }
    }

    fun apply(controller: SessionController, intent: Intent?) {
        val extras = intent?.extras ?: return
        fun extra(key: String): String? = extras.getString(PREFIX + key)
        // `role` alone is enough to arm the hook; without it this is an
        // ordinary launch. Logged unconditionally so a harness that sees
        // nothing here knows the extras never arrived.
        val role = extra("role")
        Log.i(TAG, "autostart: role=${role ?: "(none)"} active=${if (controller.isSessionActive) "yes" else "no"}")
        if (role == null || controller.isSessionActive) return

        controller.testHooks = Hooks(
            autoTrustIncoming = extra("trust_incoming") == "1",
            autosend = extra("send"),
        )
        extra("channel")?.let { SignalChannel.fromWire(it) }?.let { controller.setChannel(it) }

        // Idempotent across relaunches, which any multi-step test needs:
        // `setIdentity` clears the self-card and the whole trusted list, so
        // re-applying the key this device already has would throw away the
        // pairing the previous step just established. Only adopt a new key.
        val provided = extra("nsec")
        if (provided != null && provided != controller.identitySecret) {
            if (!controller.setIdentity(provided)) {
                Log.e(TAG, "autostart: could not persist the identity — secret store write failed")
                return
            }
        } else if (controller.identitySecret == null) {
            if (!controller.setIdentity(SessionController.generateIdentity())) {
                Log.e(TAG, "autostart: could not persist the identity — secret store write failed")
                return
            }
        }
        val name = extra("name") ?: "phone"
        if (controller.deviceName != name || controller.selfCard == null) {
            if (!controller.saveName(name)) {
                Log.e(TAG, "autostart: could not name this device — no suffix or no card")
                return
            }
        }
        extra("peer_card")?.let { controller.importPeerCard(it)?.let { err -> Log.w(TAG, "autostart: peer_card: $err") } }
        Log.i(TAG, "autostart: ${controller.displayIdentity ?: "?"} ready, ${controller.peers.size} trusted, starting role=$role")

        when (role) {
            "connect" -> {
                val key = extra("peer")
                controller.peers.firstOrNull { it.id == key }?.let { controller.connect(it) }
                    ?: Log.e(TAG, "autostart: peer $key is not trusted")
            }
            "card_host" -> controller.startCardHost()
            "card_join" -> {
                val pin = extra("pin")?.let { SessionController.normalizePin(it) }
                if (pin != null) controller.joinCardSetup(pin, extra("ip")) else Log.e(TAG, "autostart: invalid pin")
            }
        }
    }
}

package com.andrewtheguy.duocb

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A verified identity card's public detail, decoded from
 * `DuocbNative.identityCardInfo` (and from the `info` of a
 * `peer_card_received` event, which carries the same shape).
 *
 * Everything here is public by design — a card is the token you hand another
 * device so it will trust you, and it holds no private key. The one field that
 * carries weight is [fingerprint]: taken over the public key rather than the
 * card, so it survives a re-mint; it is this card's half of the card-setup
 * pairing code and the value a trusted-device row shows for out-of-band
 * re-checks.
 */
data class IdentityCardInfo(
    /** The full display identity, e.g. "mac-book_a7B2c3D4". */
    val name: String,
    val shortName: String,
    val suffix: String,
    /** 64-char hex — the stable key for trust, selection and deduplication. */
    val publicKey: String,
    val npub: String,
    /** Human-comparable digest of [publicKey], e.g. "A1B2 C3D4 …". */
    val fingerprint: String,
    /** The signed validity window (unix seconds). */
    val notBefore: Long,
    val notAfter: Long,
    val remainingSecs: Long,
    /** The local clock is outside the window, on either side. */
    val expired: Boolean,
    /** The early side of [expired]: a wrong clock, not a stale card. */
    val notYetValid: Boolean,
    /** Advisory, for this device's *own* card: under a week left, re-mint. */
    val needsRenewal: Boolean,
) {
    /**
     * The expiry line under a trusted-device row, matching the desktop's
     * wording — "expires 2026-09-13", "expired" once it has lapsed, or a clock
     * warning when the signed window has not opened yet.
     */
    val expiryText: String
        get() = when {
            notYetValid -> "not yet valid from ${day(notBefore)} — check this device's clock"
            expired -> "expired"
            else -> "expires $expiryDateText"
        }

    /** The expiry date alone, for a message that supplies its own wording. */
    val expiryDateText: String get() = day(notAfter)

    companion object {
        /**
         * Decode a signed card. null when it does not parse, is not correctly
         * signed, or breaks the schema — the same verifying path a pasted card
         * takes on the desktop.
         */
        fun parse(card: String): IdentityCardInfo? =
            DuocbNative.identityCardInfo(card)?.let { decode(it) }

        fun decode(json: String): IdentityCardInfo? =
            runCatching { JSONObject(json) }.getOrNull()?.let { decode(it) }

        fun decode(obj: JSONObject): IdentityCardInfo? {
            val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val publicKey = obj.optString("public_key").takeIf { it.isNotEmpty() } ?: return null
            return IdentityCardInfo(
                name = name,
                shortName = obj.optString("short_name", name),
                suffix = obj.optString("suffix", ""),
                publicKey = publicKey,
                npub = obj.optString("npub", ""),
                fingerprint = obj.optString("fingerprint", ""),
                notBefore = obj.optLong("not_before", 0),
                notAfter = obj.optLong("not_after", 0),
                remainingSecs = obj.optLong("remaining_secs", 0),
                expired = obj.optBoolean("expired", false),
                notYetValid = obj.optBoolean("not_yet_valid", false),
                needsRenewal = obj.optBoolean("needs_renewal", false),
            )
        }

        private fun day(unixSecs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(unixSecs * 1000))
    }
}

/**
 * One row of the trusted-device list: a peer's stored signed card plus its
 * decoded detail. Identified by public key, which is what trust is keyed on
 * and what a session names.
 */
data class TrustedPeer(
    /** The signed card as stored, handed back to the FFI verbatim. */
    val card: String,
    val info: IdentityCardInfo,
) {
    val id: String get() = info.publicKey

    companion object {
        fun from(card: String): TrustedPeer? = IdentityCardInfo.parse(card)?.let { TrustedPeer(card, it) }
    }
}

/**
 * Where the rendezvous records are put and looked for. Governs card setup and
 * clipboard sessions alike, so it reads as "how the two devices find each
 * other", not "how card setup works".
 *
 * The desktop fixes this at launch (`--lan-only` / `--nostr-only`). A phone has
 * no command line, so it is a setting, applied when a session starts — the
 * same guarantee in practice, since a running session never changes channel
 * underneath itself. **Both devices must be reachable on a channel they share.**
 */
enum class SignalChannel(val wire: String, val title: String) {
    /** The default: local network first, nostr relays as fallback. */
    LAN_THEN_NOSTR("lan_then_nostr", "Local network, then internet"),

    /** No third-party server at all — a pair with no internet still works. */
    LAN_ONLY("lan_only", "Local network only"),

    /** Relays only: no mDNS query, no side-channel listener. */
    NOSTR_ONLY("nostr_only", "Internet only"),
    ;

    val note: String
        get() = when (this) {
            LAN_THEN_NOSTR ->
                "The host publishes on the local network and relay servers. The dialer tries " +
                    "the local network first, then relays; choose Local network only when no " +
                    "third-party server may be contacted. Clipboard-session relay events expose " +
                    "both devices' application public keys as metadata even though the " +
                    "connection id is encrypted."
            LAN_ONLY ->
                "No third-party server is involved and no internet is needed, but both devices " +
                    "must be on the same network."
            NOSTR_ONLY ->
                "Relay servers only — no local network lookup at all. Useful when multicast is " +
                    "blocked, and the only option when the devices are on different networks and " +
                    "you want to skip the local search."
        }

    /** Whether the local network takes part — what makes the host-IP entry meaningful. */
    val usesLan: Boolean get() = this != NOSTR_ONLY

    /**
     * A one-line hub badge naming a non-default channel; null on the default,
     * where the card-setup screen's note is the only place it is spelled out.
     */
    val badge: String? get() = if (this == LAN_THEN_NOSTR) null else title

    companion object {
        fun fromWire(wire: String): SignalChannel? = entries.firstOrNull { it.wire == wire }
    }
}

package com.andrewtheguy.duocb

import org.json.JSONArray
import org.json.JSONObject

/**
 * The non-secret half of this installation's state: the device name, this
 * device's signed self-card, trusted peers' cards, and the saved channel.
 * Shared fields mirror the desktop config (`crates/duocb/src/config.rs`) and
 * the iOS `ConfigStore` under the same snake_case names; `channel` is
 * mobile-only persisted state because the desktop chooses it at launch. The
 * desktop's `identity_secret` and `device_suffix` are deliberately absent —
 * those live in the secret store ([DuocbSecrets]).
 *
 * Pure Kotlin (org.json only) so the JSON round trip is unit-tested on the
 * JVM; the file I/O is [ConfigStore].
 */
data class DuocbConfig(
    val version: Int = CURRENT_VERSION,
    val myName: String? = null,
    val selfCard: String? = null,
    val peers: List<String> = emptyList(),
    val channel: SignalChannel = SignalChannel.LAN_THEN_NOSTR,
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("my_name", myName ?: JSONObject.NULL)
        obj.put("self_card", selfCard ?: JSONObject.NULL)
        obj.put("peers", JSONArray(peers))
        obj.put("channel", channel.wire)
        return obj.toString(2)
    }

    companion object {
        const val CURRENT_VERSION = 1

        val EMPTY = DuocbConfig()

        /**
         * Decode a stored document. Tolerant of *missing optional* keys in
         * otherwise valid JSON — a file edited by hand still yields the fields
         * it does carry rather than throwing away a trusted-device list over an
         * absent `channel`. Tolerance stops at the shape: `version` is
         * required (a file without one is not a file this build wrote) and is
         * checked against [CURRENT_VERSION] by [ConfigStore.load], which
         * refuses anything else outright. There is no migration path and
         * there is not meant to be one.
         *
         * Throws (org.json's exceptions) on malformed JSON or a missing version.
         */
        fun fromJson(json: String): DuocbConfig {
            val obj = JSONObject(json)
            val peers = obj.optJSONArray("peers")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, null) }
            } ?: emptyList()
            return DuocbConfig(
                version = obj.getInt("version"),
                myName = obj.optString("my_name").takeIf { !obj.isNull("my_name") && it.isNotEmpty() },
                selfCard = obj.optString("self_card").takeIf { !obj.isNull("self_card") && it.isNotEmpty() },
                peers = peers,
                channel = obj.optString("channel").let { SignalChannel.fromWire(it) } ?: SignalChannel.LAN_THEN_NOSTR,
            )
        }
    }
}

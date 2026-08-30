package com.andrewtheguy.duocb

import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * A clipboard item that passed through the session — a received item in the
 * inbox, or the last item sent in the outbox. Lives only in memory, never
 * written to disk. Mirrors the desktop's ClipItem (crates/duocb/src/app/item.rs)
 * and the iOS one: same CRC-32/ISO-HDLC fingerprint and `XXXX-XXXX` display so
 * the two devices' readouts can be compared by eye.
 */
data class ClipItem(
    val text: String,
    /** When it was received (inbox) or sent (outbox), epoch millis. */
    val timestamp: Long = System.currentTimeMillis(),
    val id: Long = nextId.incrementAndGet(),
    /** When the peek view was opened, or null if collapsed; auto-hides after [PEEK_TIMEOUT_MS]. */
    val peekedAt: Long? = null,
) {
    /** CRC-32 of the payload bytes. */
    val crc32: Long = crc32(text)

    val expanded: Boolean get() = peekedAt != null

    /** Two four-hex groups, identical to the desktop's `crc32_display`. */
    val crcDisplay: String get() =
        String.format(Locale.ROOT, "%04X-%04X", (crc32 ushr 16) and 0xFFFF, crc32 and 0xFFFF)

    val sizeDisplay: String get() = formatBytes(text.toByteArray(Charsets.UTF_8).size.toLong())

    /** The peek text, truncated to [PEEK_LIMIT] characters like the desktop. */
    val peekText: String get() = if (text.length > PEEK_LIMIT) text.substring(0, PEEK_LIMIT) + "…" else text

    companion object {
        /** Max characters shown in the peek view (desktop PEEK_LIMIT). */
        const val PEEK_LIMIT = 4096

        /** How long a peeked item stays open before auto-hiding (desktop PEEK_TIMEOUT). */
        const val PEEK_TIMEOUT_MS = 15_000L

        private val nextId = AtomicLong()

        /** CRC-32/ISO-HDLC over the UTF-8 bytes — what java.util.zip computes. */
        fun crc32(text: String): Long = CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }.value

        /** Binary units, one decimal above bytes: "12 B", "1.5 KiB", "2.0 MiB". */
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
            else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
        }
    }
}

/** One live connection path (direct or relay), decoded from a `conn_path` event. */
data class ConnPath(
    /** "direct" | "relay" | "other" */
    val kind: String,
    /** A human line like "Direct 1.2.3.4:52186 (rtt 1ms)". */
    val display: String,
    /** Whether iroh currently routes traffic over this path. */
    val selected: Boolean,
) {
    companion object {
        fun parse(array: JSONArray?): List<ConnPath> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let {
                    ConnPath(
                        kind = it.optString("kind", "other"),
                        display = it.optString("display", ""),
                        selected = it.optBoolean("selected", false),
                    )
                }
            }
        }
    }
}

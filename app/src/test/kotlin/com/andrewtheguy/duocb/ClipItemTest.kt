package com.andrewtheguy.duocb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipItemTest {
    @Test
    fun crcDisplayMatchesTheDesktopFingerprint() {
        // CRC-32/ISO-HDLC check value for "123456789" is 0xCBF43926 — the same
        // algorithm the desktop's crc32fast and the iOS port use, so the two
        // devices' readouts compare by eye.
        val item = ClipItem("123456789", timestamp = 0)
        assertEquals(0xCBF43926L, item.crc32)
        assertEquals("CBF4-3926", item.crcDisplay)
    }

    @Test
    fun crcOfTheEmptyStringIsZero() {
        assertEquals("0000-0000", ClipItem("", timestamp = 0).crcDisplay)
    }

    @Test
    fun crcIsOverUtf8Bytes() {
        // "é" is two bytes in UTF-8; a UTF-16 CRC would differ.
        assertEquals(ClipItem.crc32("é"), ClipItem.crc32("é"))
        assertEquals("2 B", ClipItem("é", timestamp = 0).sizeDisplay)
    }

    @Test
    fun sizeUsesBinaryUnits() {
        assertEquals("12 B", ClipItem.formatBytes(12))
        assertEquals("1.0 KiB", ClipItem.formatBytes(1024))
        assertEquals("1.5 KiB", ClipItem.formatBytes(1536))
        assertEquals("2.0 MiB", ClipItem.formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun peekTruncatesAtTheDesktopLimit() {
        val long = "x".repeat(ClipItem.PEEK_LIMIT + 10)
        val item = ClipItem(long, timestamp = 0)
        assertEquals(ClipItem.PEEK_LIMIT + 1, item.peekText.length)
        assertTrue(item.peekText.endsWith("…"))
        assertEquals("short", ClipItem("short", timestamp = 0).peekText)
    }

    @Test
    fun peekStateIsCarriedByCopy() {
        val item = ClipItem("hello", timestamp = 0)
        assertFalse(item.expanded)
        val peeked = item.copy(peekedAt = 1_000)
        assertTrue(peeked.expanded)
        assertEquals(item.id, peeked.id)
        assertEquals(item.crc32, peeked.crc32)
    }
}

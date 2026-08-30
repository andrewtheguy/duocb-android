package com.andrewtheguy.duocb

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DuocbConfigTest {
    @Test
    fun roundTripsEveryField() {
        val config = DuocbConfig(
            myName = "pixel",
            selfCard = "{\"card\":1}",
            peers = listOf("{\"card\":2}", "{\"card\":3}"),
            channel = SignalChannel.NOSTR_ONLY,
        )
        assertEquals(config, DuocbConfig.fromJson(config.toJson()))
    }

    @Test
    fun usesTheDesktopFieldNames() {
        val obj = JSONObject(DuocbConfig(myName = "pixel", selfCard = "c").toJson())
        assertEquals(DuocbConfig.CURRENT_VERSION, obj.getInt("version"))
        assertEquals("pixel", obj.getString("my_name"))
        assertEquals("c", obj.getString("self_card"))
        assertEquals(0, obj.getJSONArray("peers").length())
        assertEquals("lan_then_nostr", obj.getString("channel"))
    }

    @Test
    fun emptyConfigWritesNullsNotStrings() {
        val obj = JSONObject(DuocbConfig.EMPTY.toJson())
        assertEquals(true, obj.isNull("my_name"))
        assertEquals(true, obj.isNull("self_card"))
        assertEquals(DuocbConfig.EMPTY, DuocbConfig.fromJson(obj.toString()))
    }

    @Test
    fun toleratesMissingOptionalKeys() {
        // A hand-edited file still yields what it does carry — the trusted
        // list is not thrown away over an absent channel.
        val config = DuocbConfig.fromJson("""{"version":1,"peers":["a"]}""")
        assertEquals(listOf("a"), config.peers)
        assertNull(config.myName)
        assertNull(config.selfCard)
        assertEquals(SignalChannel.LAN_THEN_NOSTR, config.channel)
    }

    @Test
    fun unknownChannelFallsBackToTheDefault() {
        assertEquals(
            SignalChannel.LAN_THEN_NOSTR,
            DuocbConfig.fromJson("""{"version":1,"channel":"carrier-pigeon"}""").channel,
        )
    }

    @Test
    fun versionIsRequired() {
        assertThrows(JSONException::class.java) { DuocbConfig.fromJson("""{"peers":[]}""") }
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(JSONException::class.java) { DuocbConfig.fromJson("not json") }
    }

    @Test
    fun channelWireNamesMatchTheFfi() {
        assertEquals(SignalChannel.LAN_THEN_NOSTR, SignalChannel.fromWire("lan_then_nostr"))
        assertEquals(SignalChannel.LAN_ONLY, SignalChannel.fromWire("lan_only"))
        assertEquals(SignalChannel.NOSTR_ONLY, SignalChannel.fromWire("nostr_only"))
        assertNull(SignalChannel.fromWire("lan"))
        assertEquals(true, SignalChannel.LAN_ONLY.usesLan)
        assertEquals(false, SignalChannel.NOSTR_ONLY.usesLan)
        assertNull(SignalChannel.LAN_THEN_NOSTR.badge)
    }
}

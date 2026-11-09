package com.yingti.app.ui

import org.junit.Assert.*
import org.junit.Test

class DeviceAliasTest {
    @Test fun normalizeTrimsWhitespace() {
        assertEquals("卧室", DeviceAlias.normalize("  卧室  "))
    }

    @Test fun blankInputRestoresDefault() {
        assertNull(DeviceAlias.normalize(""))
        assertNull(DeviceAlias.normalize("   "))
    }

    @Test fun longInputTruncatedToMaxLength() {
        assertEquals("a".repeat(20), DeviceAlias.normalize("a".repeat(30)))
        assertEquals(20, DeviceAlias.length(DeviceAlias.normalize("a".repeat(30))!!))
    }

    @Test fun surrogatePairsCountedAsOneAndNotSplit() {
        val emoji = "😀"
        assertEquals(1, DeviceAlias.length(emoji))
        val input = "a".repeat(19) + emoji + "b"
        val normalized = DeviceAlias.normalize(input)
        assertEquals("a".repeat(19) + emoji, normalized)
        assertEquals(20, DeviceAlias.length(normalized!!))
    }

    @Test fun displayNamePrecedence() {
        assertEquals("卧室", DeviceAlias.displayName("卧室", "SX589B-1234"))
        assertEquals("SX589B-1234", DeviceAlias.displayName("  ", "SX589B-1234"))
        assertEquals("SX589B-1234", DeviceAlias.displayName(null, "SX589B-1234"))
        assertEquals("SX589B", DeviceAlias.displayName(null, null))
    }
}

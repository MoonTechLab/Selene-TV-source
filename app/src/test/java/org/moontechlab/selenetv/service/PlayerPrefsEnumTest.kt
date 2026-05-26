package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.service.AppDataService.PlayerType
import org.moontechlab.selenetv.service.AppDataService.DecodeMode

class PlayerPrefsEnumTest {
    @Test fun playerType_fromKey_roundTrip() {
        assertEquals(PlayerType.MPV, PlayerType.fromKey("mpv"))
        assertEquals(PlayerType.EXOPLAYER, PlayerType.fromKey("exoplayer"))
    }
    @Test fun playerType_unknownKey_fallsBackToExo() {
        assertEquals(PlayerType.EXOPLAYER, PlayerType.fromKey("garbage"))
    }
    @Test fun playerType_fromDisplayName() {
        assertEquals(PlayerType.MPV, PlayerType.fromDisplayName("MPV"))
        assertEquals(PlayerType.EXOPLAYER, PlayerType.fromDisplayName("不存在"))
    }
    @Test fun decodeMode_fromKey_roundTrip() {
        assertEquals(DecodeMode.SOFTWARE, DecodeMode.fromKey("software"))
        assertEquals(DecodeMode.HARDWARE, DecodeMode.fromKey("hardware"))
    }
    @Test fun decodeMode_unknownKey_fallsBackToHardware() {
        assertEquals(DecodeMode.HARDWARE, DecodeMode.fromKey("garbage"))
    }
    @Test fun decodeMode_fromDisplayName() {
        assertEquals(DecodeMode.SOFTWARE, DecodeMode.fromDisplayName("软解"))
        assertEquals(DecodeMode.HARDWARE, DecodeMode.fromDisplayName("硬解"))
    }
}

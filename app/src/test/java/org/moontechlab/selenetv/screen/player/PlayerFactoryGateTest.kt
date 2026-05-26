package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.service.AppDataService.PlayerType

class PlayerFactoryGateTest {
    @Test fun mpv_allowedOnApi26AndAbove() {
        assertEquals(PlayerType.MPV, resolveEffectivePlayerType(PlayerType.MPV, 26))
        assertEquals(PlayerType.MPV, resolveEffectivePlayerType(PlayerType.MPV, 34))
    }
    @Test fun mpv_downgradedBelowApi26() {
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.MPV, 25))
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.MPV, 23))
    }
    @Test fun exo_alwaysExo() {
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.EXOPLAYER, 23))
        assertEquals(PlayerType.EXOPLAYER, resolveEffectivePlayerType(PlayerType.EXOPLAYER, 34))
    }
}

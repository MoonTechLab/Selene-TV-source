package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsResolutionProbeGuardTest {

    /** 在 0、188、376 偏移放置 TS 同步字节 0x47,守卫应识别为 TS。 */
    @Test
    fun looksLikeTs_recognizesSyncPattern() {
        val bytes = ByteArray(188 * 3)
        bytes[0] = 0x47
        bytes[188] = 0x47
        bytes[376] = 0x47
        assertTrue(TsResolutionProbe.looksLikeTs(bytes))
    }

    @Test
    fun looksLikeTs_rejectsPlaylistText() {
        val m3u8 = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n".toByteArray()
        assertFalse(TsResolutionProbe.looksLikeTs(m3u8))
    }

    @Test
    fun looksLikeTs_rejectsTooShort() {
        assertFalse(TsResolutionProbe.looksLikeTs(ByteArray(10)))
        assertFalse(TsResolutionProbe.looksLikeTs(null))
    }

    /** 非 TS 字节在触达 TsExtractor 前即返回 0,可在 JVM 下运行。 */
    @Test
    fun parseWidth_returnsZeroForNonTs() {
        val m3u8 = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n".toByteArray()
        assertEquals(0, TsResolutionProbe.parseWidth(m3u8))
        assertEquals(0, TsResolutionProbe.parseWidth(null))
    }
}

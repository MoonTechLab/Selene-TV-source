package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAdFilterTest {
    private val baseUrl = "https://media.example/video/index.m3u8"

    @Test
    fun removesSegmentsInsideExplicitCueAdBreak() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:8.0,
            segment-1.ts
            #EXT-X-CUE-OUT:30
            #EXTINF:6.0,
            ad-1.ts
            #EXTINF:6.0,
            ad-2.ts
            #EXT-X-CUE-IN
            #EXTINF:8.0,
            segment-2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsAdFilter.filterPlaylist(
            playlist, baseUrl, HlsAdFilter.Rules(enabled = true),
        )

        assertTrue(result.contains("segment-1.ts"))
        assertTrue(result.contains("segment-2.ts"))
        assertFalse(result.contains("ad-1.ts"))
        assertFalse(result.contains("ad-2.ts"))
        assertFalse(result.contains("CUE-OUT"))
        assertFalse(result.contains("CUE-IN"))
    }

    @Test
    fun removesOnlyUrlsMatchedByConfiguredRegex() {
        val playlist = """
            #EXTM3U
            #EXTINF:1.0,
            https://cdn.example/normal.ts
            #EXTINF:1.0,
            https://cdn.example/advert-001.ts
        """.trimIndent()

        val result = HlsAdFilter.filterPlaylist(
            playlist,
            baseUrl,
            HlsAdFilter.Rules(enabled = true, urlRegexRules = listOf("advert-[0-9]+\\.ts")),
        )

        assertTrue(result.contains("normal.ts"))
        assertFalse(result.contains("advert-001.ts"))
    }

    @Test
    fun domainRuleMatchesExactHostAndSubdomainsOnly() {
        val playlist = """
            #EXTM3U
            #EXTINF:5.0,
            https://ads.example/ad.ts
            #EXTINF:5.0,
            https://sub.ads.example/ad2.ts
            #EXTINF:5.0,
            https://notads.example/keep.ts
        """.trimIndent()

        val result = HlsAdFilter.filterPlaylist(
            playlist, baseUrl,
            HlsAdFilter.Rules(enabled = true, adDomains = listOf("ads.example")),
        )

        assertFalse(result.contains("ads.example/ad.ts"))
        assertFalse(result.contains("sub.ads.example/ad2.ts"))
        assertTrue(result.contains("notads.example/keep.ts"))
    }

    @Test
    fun malformedRegexIsIgnoredWithoutBreakingPlaylist() {
        val playlist = """
            #EXTM3U
            #EXTINF:4.0,
            keep.ts
        """.trimIndent()

        val result = HlsAdFilter.filterPlaylist(
            playlist, baseUrl,
            HlsAdFilter.Rules(enabled = true, urlRegexRules = listOf("[invalid")),
        )

        assertEquals(playlist, result)
    }

    @Test
    fun disabledFilterLeavesPlaylistUnchanged() {
        val playlist = "#EXTM3U\n#EXTINF:1.0,\nad.ts"
        assertEquals(
            playlist,
            HlsAdFilter.filterPlaylist(playlist, baseUrl, HlsAdFilter.Rules(enabled = false)),
        )
    }

    @Test
    fun shortSegmentsAreNotRemovedWithoutExplicitRule() {
        val playlist = """
            #EXTM3U
            #EXTINF:0.5,
            short-intro.ts
            #EXTINF:0.8,
            short-scene.ts
        """.trimIndent()

        val result = HlsAdFilter.filterPlaylist(
            playlist, baseUrl, HlsAdFilter.Rules(enabled = true),
        )

        assertTrue(result.contains("short-intro.ts"))
        assertTrue(result.contains("short-scene.ts"))
    }

    @Test
    fun masterPlaylistVariantUrisAreNotTreatedAsSegments() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            https://ads.example/variant.m3u8
        """.trimIndent()

        val result = HlsAdFilter.filterPlaylist(
            playlist, baseUrl,
            HlsAdFilter.Rules(enabled = true, adDomains = listOf("ads.example")),
        )

        assertEquals(playlist, result)
    }
}

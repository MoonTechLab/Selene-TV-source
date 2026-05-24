package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SearchResult

class SourceFilterTest {
    private fun result(
        title: String = "长安的荔枝",
        year: String = "2025",
        doubanId: Long? = null,
        source: String = "src",
        id: String = "1",
        typeName: String? = null,
    ) = SearchResult(
        id = id,
        title = title,
        poster = "",
        episodes = listOf("ep1"),
        episodesTitles = listOf("第1集"),
        source = source,
        sourceName = source,
        year = year,
        typeName = typeName,
        doubanId = doubanId,
    )

    @Test
    fun `strong match by douban id passes`() {
        val r = result(doubanId = 12345)
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `wrong douban id is rejected even if title matches`() {
        val r = result(doubanId = 99999, title = "长安的荔枝", year = "2025")
        assertFalse(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `null douban id falls back to title and year match`() {
        val r = result(doubanId = null, title = "长安的荔枝", year = "2025")
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `null douban id with different title is rejected`() {
        val r = result(doubanId = null, title = "其他作品", year = "2025")
        assertFalse(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `null douban id with empty route year accepts any year`() {
        val r = result(doubanId = null, title = "长安的荔枝", year = "2024")
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = ""))
    }

    @Test
    fun `null douban id with empty result year accepts any route year`() {
        val r = result(doubanId = null, title = "长安的荔枝", year = "")
        assertTrue(SourceFilter.matchesContent(r, doubanId = "12345", title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear matches same title and year`() {
        val r = result(title = "长安的荔枝", year = "2025", doubanId = 999)
        assertTrue(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear ignores douban id`() {
        val r = result(title = "长安的荔枝", year = "2025", doubanId = 12345)
        assertTrue(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear rejects different title`() {
        val r = result(title = "其他作品", year = "2025")
        assertFalse(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = "2025"))
    }

    @Test
    fun `matchesByTitleYear accepts when route year empty`() {
        val r = result(title = "长安的荔枝", year = "2024")
        assertTrue(SourceFilter.matchesByTitleYear(r, title = "长安的荔枝", year = ""))
    }
}

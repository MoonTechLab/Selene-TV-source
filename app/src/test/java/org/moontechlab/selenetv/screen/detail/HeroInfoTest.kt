package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.moontechlab.selenetv.model.BangumiImages
import org.moontechlab.selenetv.model.BangumiRating
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.SearchResult

class HeroInfoTest {
    @Test
    fun `searchResult maps lean fields`() {
        val r = SearchResult(
            id = "1", title = "长安的荔枝", poster = "p.jpg",
            episodes = listOf("ep1", "ep2"), episodesTitles = listOf("第1集", "第2集"),
            source = "src", sourceName = "源A", year = "2025",
            desc = "简介文本", typeName = "电视剧", doubanId = null,
        )
        val h = r.toHeroInfo()
        assertEquals("长安的荔枝", h.title)
        assertEquals("p.jpg", h.poster)
        assertNull(h.rate)
        assertEquals(listOf("电视剧"), h.genres)
        assertEquals("2025", h.subLine)
        assertEquals("简介文本", h.summary)
    }

    @Test
    fun `doubanDetails maps rate and sub line`() {
        val d = DoubanMovieDetails(
            id = "123", title = "电影名", poster = "dp.jpg", rate = "8.5", year = "2024",
            summary = "豆瓣简介", genres = listOf("剧情", "悬疑"),
            directors = emptyList(), screenwriters = emptyList(), actors = emptyList(),
            duration = "120分钟", countries = listOf("中国"), languages = emptyList(),
            releaseDate = "2024-01-01", recommends = emptyList(),
        )
        val h = d.toHeroInfo()
        assertEquals("电影名", h.title)
        assertEquals("8.5", h.rate)
        assertEquals(listOf("剧情", "悬疑"), h.genres)
        assertEquals("2024-01-01(中国) · 中国 · 120分钟", h.subLine)
        assertEquals("豆瓣简介", h.summary)
    }

    @Test
    fun `bangumiDetails maps name and score`() {
        val images = BangumiImages(large = "l.jpg", common = "", medium = "", small = "", grid = "")
        val b = org.moontechlab.selenetv.model.BangumiDetails(
            id = 9, type = 2, name = "EN Name", nameCn = "中文名", summary = "番剧简介",
            nsfw = false, locked = false, date = "2025-04-01", platform = "TV", images = images,
            infobox = emptyList(), volumes = 0, eps = 12, totalEpisodes = 12,
            rating = BangumiRating(total = 100, count = emptyMap(), score = 7.8),
            collection = org.moontechlab.selenetv.model.BangumiCollection(),
            tags = listOf(org.moontechlab.selenetv.model.BangumiTag("奇幻")),
            metaTags = listOf("TV", "原创"), series = false,
        )
        val h = b.toHeroInfo()
        assertEquals("中文名", h.title)
        assertEquals("l.jpg", h.poster)
        assertEquals("7.8", h.rate)
        assertEquals(listOf("TV", "原创"), h.genres)
        assertEquals("2025-04-01", h.subLine)
        assertEquals("番剧简介", h.summary)
    }
}

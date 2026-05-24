package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.SearchResult

class DetailSourceResolverTest {
    private fun result(
        id: String, source: String, title: String = "长安的荔枝",
        year: String = "2025", episodes: Int = 10, doubanId: Long? = null,
    ) = SearchResult(
        id = id, title = title, poster = "", episodes = List(episodes) { "ep$it" },
        episodesTitles = List(episodes) { "第${it + 1}集" }, source = source,
        sourceName = source, year = year, doubanId = doubanId,
    )

    @Test
    fun `historyGroupSources returns group containing source and id`() {
        val raw = listOf(
            result(id = "a", source = "s1"),
            result(id = "b", source = "s2"),
            result(id = "x", source = "s3", title = "别的剧", year = "2020", episodes = 1),
        )
        val group = DetailSourceResolver.historyGroupSources(raw, source = "s1", id = "a")
        assertTrue(group != null)
        assertEquals(2, group!!.size) // s1/a 与 s2/b 同 title_year_type 聚合
        assertTrue(group.any { it.source == "s1" && it.id == "a" })
    }

    @Test
    fun `historyGroupSources returns null when source not found`() {
        val raw = listOf(result(id = "a", source = "s1"))
        assertNull(DetailSourceResolver.historyGroupSources(raw, source = "sX", id = "zzz"))
    }

    @Test
    fun `tier2DoubanId picks id appearing at least twice`() {
        val sources = listOf(
            result(id = "a", source = "s1", doubanId = 111),
            result(id = "b", source = "s2", doubanId = 111),
            result(id = "c", source = "s3", doubanId = 222),
        )
        assertEquals("111", DetailSourceResolver.tier2DoubanId(sources))
    }

    @Test
    fun `tier2DoubanId returns null when no id repeats`() {
        val sources = listOf(
            result(id = "a", source = "s1", doubanId = 111),
            result(id = "b", source = "s2", doubanId = 222),
            result(id = "c", source = "s3", doubanId = null),
        )
        assertNull(DetailSourceResolver.tier2DoubanId(sources))
    }
}

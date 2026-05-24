package org.moontechlab.selenetv.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.moontechlab.selenetv.model.AggregatedSearchResult
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.screen.DetailEntry

class DetailUiStateHeroInfoTest {
    private fun source(id: String, doubanId: Long? = null) = SearchResult(
        id = id, title = "标题", poster = "p.jpg", episodes = listOf("ep1"),
        episodesTitles = listOf("第1集"), source = "s$id", sourceName = "源$id",
        year = "2025", desc = "源简介", typeName = "电视剧", doubanId = doubanId,
    )

    private val douban = DoubanMovieDetails(
        id = "111", title = "豆瓣标题", poster = "dp.jpg", rate = "8.0", year = "2025",
        summary = "豆瓣简介", genres = listOf("剧情"), directors = emptyList(),
        screenwriters = emptyList(), actors = emptyList(), countries = emptyList(),
        languages = emptyList(), recommends = emptyList(),
    )

    @Test
    fun `history shows selected source info when no tier2 douban`() {
        val src = source("a")
        val state = DetailUiState(
            entry = DetailEntry.History("sa", "a", "搜索名", "标题", "p.jpg", "2025"),
            sources = listOf(src),
            selectedSourceKey = "sa+a",
        )
        assertEquals("源简介", state.heroInfo?.summary)
    }

    @Test
    fun `history shows douban info when tier2 douban fetched`() {
        val src = source("a")
        val state = DetailUiState(
            entry = DetailEntry.History("sa", "a", "搜索名", "标题", "p.jpg", "2025"),
            sources = listOf(src),
            selectedSourceKey = "sa+a",
            details = douban, // tier2 已拉到
        )
        assertEquals("豆瓣标题", state.heroInfo?.title)
        assertEquals("8.0", state.heroInfo?.rate)
    }

    @Test
    fun `douban entry uses douban details only`() {
        val state = DetailUiState(
            entry = DetailEntry.Douban("111", "标题", "p.jpg", "2025"),
            details = douban,
        )
        assertEquals("豆瓣标题", state.heroInfo?.title)
    }

    @Test
    fun `search aggregate with no selection and no douban yields null hero`() {
        val agg = AggregatedSearchResult(
            key = "标题_2025_tv", title = "标题", year = "2025", type = "tv", cover = "p.jpg",
        )
        val state = DetailUiState(
            entry = DetailEntry.SearchAggregate(agg),
        )
        assertNull(state.heroInfo)
    }
}

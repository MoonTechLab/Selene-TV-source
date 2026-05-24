package org.moontechlab.selenetv.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.VideoInfo

class DetailEntryTest {
    private fun video(
        doubanId: String? = null, bangumiId: Int? = null,
        source: String = "src", id: String = "1", searchTitle: String = "搜索名",
    ) = VideoInfo(
        id = id, source = source, title = "标题", sourceName = "源", year = "2025",
        cover = "c.jpg", index = 0, totalEpisodes = 1, playTime = 0, totalTime = 0,
        saveTime = 0, searchTitle = searchTitle, doubanId = doubanId, bangumiId = bangumiId,
    )

    @Test
    fun `douban id maps to Douban entry`() {
        val e = detailEntryFor(video(doubanId = "123"))
        assertTrue(e is DetailEntry.Douban)
        assertEquals("123", (e as DetailEntry.Douban).doubanId)
    }

    @Test
    fun `bangumi id maps to Bangumi entry`() {
        val e = detailEntryFor(video(bangumiId = 9))
        assertTrue(e is DetailEntry.Bangumi)
        assertEquals(9, (e as DetailEntry.Bangumi).bangumiId)
    }

    @Test
    fun `douban id takes priority over bangumi id`() {
        val e = detailEntryFor(video(doubanId = "123", bangumiId = 9))
        assertTrue(e is DetailEntry.Douban)
    }

    @Test
    fun `no id maps to History entry`() {
        val e = detailEntryFor(video(source = "s2", id = "42", searchTitle = "搜索名"))
        assertTrue(e is DetailEntry.History)
        e as DetailEntry.History
        assertEquals("s2", e.source)
        assertEquals("42", e.id)
        assertEquals("搜索名", e.searchTitle)
    }

    @Test
    fun `blank searchTitle falls back to title for History entry`() {
        // 部分播放记录的 searchTitle 为空，回退到 title，避免空查询导致搜索抛异常崩溃
        val e = detailEntryFor(video(searchTitle = ""))
        assertTrue(e is DetailEntry.History)
        assertEquals("标题", (e as DetailEntry.History).searchTitle)
    }
}

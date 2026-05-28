package org.moontechlab.selenetv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.FavoriteItem
import org.moontechlab.selenetv.model.PlayRecord

class LocalUserDataRepositoryHelpersTest {
    @Test fun upsertSearchHistory_trims_and_deduplicates_keeping_newest_first() {
        val existing = listOf("foo", "bar", "baz")
        assertEquals(listOf("bar", "foo", "baz"), LocalUserDataRepository.upsertSearchHistory(existing, " bar "))
    }

    @Test fun upsertSearchHistory_caps_at_20() {
        val existing = (1..20).map { "q$it" }  // q1..q20
        val out = LocalUserDataRepository.upsertSearchHistory(existing, "new")
        assertEquals(20, out.size)
        assertEquals("new", out.first())
        assertEquals("q19", out.last())  // q20 被踢出
    }

    @Test fun upsertSearchHistory_ignores_blank() {
        val existing = listOf("a")
        assertEquals(existing, LocalUserDataRepository.upsertSearchHistory(existing, "   "))
    }

    @Test fun upsertFavorite_inserts_new_at_head() {
        val existing = listOf(fav("s1", "id1", saveTime = 100L))
        val out = LocalUserDataRepository.upsertFavorite(existing, fav("s2", "id2", saveTime = 200L))
        assertEquals(listOf("s2+id2", "s1+id1"), out.map { "${it.source}+${it.id}" })
    }

    @Test fun upsertFavorite_replaces_existing_same_key() {
        val existing = listOf(fav("s1", "id1", saveTime = 100L, title = "old"))
        val out = LocalUserDataRepository.upsertFavorite(existing, fav("s1", "id1", saveTime = 200L, title = "new"))
        assertEquals(1, out.size)
        assertEquals("new", out[0].title)
        assertEquals(200L, out[0].saveTime)
    }

    @Test fun upsertPlayRecord_overwrites_same_key() {
        val k = "src+1"
        val existing = mapOf(k to record("src", "1", playTime = 10))
        val out = LocalUserDataRepository.upsertPlayRecord(existing, record("src", "1", playTime = 50))
        assertEquals(1, out.size)
        assertEquals(50L, out[k]!!.playTime)
    }

    @Test fun favoriteFromMap_builds_item_from_snake_case_keys() {
        val map: Map<String, Any?> = mapOf(
            "title" to "T",
            "source_name" to "Src",
            "year" to "2025",
            "cover" to "c.jpg",
            "total_episodes" to 12,
            "save_time" to 999L,
        )
        val item = LocalUserDataRepository.favoriteFromMap("s", "i", map)
        assertEquals("s", item.source)
        assertEquals("i", item.id)
        assertEquals("T", item.title)
        assertEquals("Src", item.sourceName)
        assertEquals("2025", item.year)
        assertEquals("c.jpg", item.cover)
        assertEquals(12, item.totalEpisodes)
        assertEquals(999L, item.saveTime)
        assertEquals("", item.origin)
    }

    private fun fav(source: String, id: String, saveTime: Long = 0L, title: String = "t") =
        FavoriteItem(id, source, title, "sn", "2025", "c", 1, saveTime, "")

    private fun record(source: String, id: String, playTime: Long = 0L) =
        PlayRecord(id, source, "t", "sn", "2025", "c", 0, 1, playTime, 0, 0, "")
}

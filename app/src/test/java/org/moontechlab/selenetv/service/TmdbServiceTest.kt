package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbServiceTest {

    /**
     * 续作的「季」后缀必须被剥离，否则 TMDB 搜不到（豆瓣返回 "ぐらんぶる Season 2"，
     * TMDB 以基础剧名 "ぐらんぶる" 收录）。回归 bug：碧蓝之海第二季无 TMDB 布局。
     */
    @Test
    fun stripSeasonSuffix_removesSeasonMarkers() {
        assertEquals("ぐらんぶる", TmdbService.stripSeasonSuffix("ぐらんぶる Season 2"))
        assertEquals("某剧", TmdbService.stripSeasonSuffix("某剧 第二季"))
        assertEquals("某剧", TmdbService.stripSeasonSuffix("某剧第2期"))
        assertEquals("Attack on Titan", TmdbService.stripSeasonSuffix("Attack on Titan 2nd Season"))
        assertEquals("Foo", TmdbService.stripSeasonSuffix("Foo Season III"))
    }

    @Test
    fun stripSeasonSuffix_keepsTitlesWithoutSeasonSuffix() {
        // 正片名里的数字/词不应被误删
        assertEquals("Blade Runner 2049", TmdbService.stripSeasonSuffix("Blade Runner 2049"))
        assertEquals("ぐらんぶる", TmdbService.stripSeasonSuffix("ぐらんぶる"))
        assertEquals("玲珑", TmdbService.stripSeasonSuffix("玲珑"))
    }

    @Test
    fun parseSeasonNumber_extractsSeason() {
        assertEquals(2, TmdbService.parseSeasonNumber("ぐらんぶる Season 2"))
        assertEquals(2, TmdbService.parseSeasonNumber("某剧 第二季"))
        assertEquals(10, TmdbService.parseSeasonNumber("某剧第十期"))
        assertEquals(23, TmdbService.parseSeasonNumber("某剧 第二十三季"))
        assertEquals(2, TmdbService.parseSeasonNumber("Attack on Titan 2nd Season"))
        assertEquals(3, TmdbService.parseSeasonNumber("Foo Season III"))
    }

    @Test
    fun parseSeasonNumber_defaultsToOne() {
        assertEquals(1, TmdbService.parseSeasonNumber("玲珑"))
        assertEquals(1, TmdbService.parseSeasonNumber("Blade Runner 2049"))
        assertEquals(1, TmdbService.parseSeasonNumber("ぐらんぶる"))
    }

    @Test
    fun parseImdbId_extractsId() {
        assertEquals("tt0944947", TmdbService.parseImdbId("""{"imdb_id":"tt0944947","tvdb_id":121361}"""))
    }

    @Test
    fun parseImdbId_nullWhenMissingOrBlank() {
        assertEquals(null, TmdbService.parseImdbId("""{"imdb_id":null}"""))
        assertEquals(null, TmdbService.parseImdbId("""{"imdb_id":""}"""))
        assertEquals(null, TmdbService.parseImdbId("""{}"""))
    }
}

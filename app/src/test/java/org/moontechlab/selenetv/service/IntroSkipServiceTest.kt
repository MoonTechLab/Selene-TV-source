package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.SkipType

class IntroSkipServiceTest {

    // 实测 fixture（GoT s1e1）
    private val tidbGot = """{"tmdb_id":1399,"type":"tv","season":1,"episode":1,"intro":[{"start_ms":437018,"end_ms":536972}],"credits":[{"start_ms":3615033,"end_ms":null}]}"""

    @Test
    fun parseTidb_mapsTypesAndNullEnd() {
        val segs = IntroSkipService.parseTidb(tidbGot)
        assertEquals(2, segs.size)
        val intro = segs.first { it.type == SkipType.INTRO }
        assertEquals(437018L, intro.startMs)
        assertEquals(536972L, intro.endMs)
        val outro = segs.first { it.type == SkipType.OUTRO }   // credits→OUTRO
        assertEquals(3615033L, outro.startMs)
        assertEquals(Long.MAX_VALUE, outro.endMs)              // end_ms:null → 哨兵
    }

    @Test
    fun parseTidb_emptyOnError() {
        assertEquals(emptyList<Any>(), IntroSkipService.parseTidb("""{"error":"media not found"}"""))
        assertEquals(emptyList<Any>(), IntroSkipService.parseTidb("garbage"))
    }

    // 实测 fixture（IntroDB GoT s1e1）
    private val introdbGot = """{"imdb_id":"tt0944947","season":1,"episode":1,"intro":{"start_ms":437000,"end_ms":531000,"confidence":1,"submission_count":1},"recap":null,"outro":{"start_ms":3631500,"end_ms":3699500,"confidence":1,"submission_count":2}}"""

    @Test
    fun parseIntroDb_parsesScoredCandidates() {
        val cands = IntroSkipService.parseIntroDb(introdbGot)
        assertEquals(2, cands.size)   // recap=null 跳过
        val intro = cands.first { it.type == SkipType.INTRO }
        assertEquals(437000L, intro.startMs)
        assertEquals(1, intro.submissionCount)
        val outro = cands.first { it.type == SkipType.OUTRO }
        assertEquals(2, outro.submissionCount)
    }

    @Test
    fun parseIntroDb_emptyOnAllNull() {
        assertEquals(emptyList<Any>(), IntroSkipService.parseIntroDb("""{"imdb_id":"tt1","season":1,"episode":1,"intro":null,"recap":null,"outro":null}"""))
    }

    @Test
    fun mergeSegments_fillsMissingTypesGatedByConfidence() {
        val tidb = listOf(org.moontechlab.selenetv.model.SkipSegment(SkipType.INTRO, 0, 90000))
        val cands = listOf(
            IntroSkipService.IntroDbCandidate(SkipType.INTRO, 1, 2, 1.0, 5),   // 重复类型→忽略
            IntroSkipService.IntroDbCandidate(SkipType.OUTRO, 100, 200, 0.6, 1), // conf≥0.5→采纳
            IntroSkipService.IntroDbCandidate(SkipType.RECAP, 5, 6, 0.2, 1),     // 低分→丢弃
        )
        val merged = IntroSkipService.mergeSegments(tidb, cands)
        assertEquals(2, merged.size)                          // INTRO(原) + OUTRO(补)
        assertEquals(SkipType.OUTRO, merged[1].type)
        assertEquals(100L, merged[1].startMs)
    }

    @Test
    fun mergeSegments_acceptsBySubmissionCount() {
        val cands = listOf(IntroSkipService.IntroDbCandidate(SkipType.OUTRO, 1, 2, 0.1, 2)) // subs≥2→采纳
        assertEquals(1, IntroSkipService.mergeSegments(emptyList(), cands).size)
    }

    @Test
    fun pickActiveSegment_matchesPositionAndClampsSentinel() {
        val segs = listOf(
            org.moontechlab.selenetv.model.SkipSegment(SkipType.INTRO, 1000, 5000),
            org.moontechlab.selenetv.model.SkipSegment(SkipType.OUTRO, 60000, Long.MAX_VALUE),
        )
        assertEquals(SkipType.INTRO, IntroSkipService.pickActiveSegment(segs, 2000, 90000)?.type)
        assertEquals(null, IntroSkipService.pickActiveSegment(segs, 5000, 90000))        // end 排他
        assertEquals(SkipType.OUTRO, IntroSkipService.pickActiveSegment(segs, 80000, 90000)?.type) // 哨兵夹到 dur
        assertEquals(null, IntroSkipService.pickActiveSegment(segs, 95000, 90000))       // 超过 dur
    }
}

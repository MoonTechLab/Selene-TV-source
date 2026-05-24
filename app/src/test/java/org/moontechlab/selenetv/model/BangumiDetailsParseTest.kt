package org.moontechlab.selenetv.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：bgm.tv /v0/subjects/{id} 的 infobox 是 {key,value} 对象数组（value 可为字符串或
 * [{v:...}] 数组），tags 是 {name,count} 对象数组。曾误将二者建模为 List<String>，导致每个
 * 番剧详情解析失败、详情页恒显「加载失败」。此测试在旧模型上无法编译/解析，在新模型上通过。
 */
class BangumiDetailsParseTest {
    // 与 BangumiService 中的配置保持一致
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `decodes real-shaped subject json with object infobox and tags`() {
        // 精简自真实接口返回，保留两种 infobox value 形态与对象型 tags
        val payload = """
            {
              "id": 265,
              "type": 2,
              "name": "新世紀エヴァンゲリオン",
              "name_cn": "新世纪福音战士",
              "summary": "简介",
              "nsfw": false,
              "locked": false,
              "date": "1995-10-04",
              "platform": "TV",
              "images": {"large":"l.jpg","common":"c.jpg","medium":"m.jpg","small":"s.jpg","grid":"g.jpg"},
              "infobox": [
                {"key": "中文名", "value": "新世纪福音战士"},
                {"key": "别名", "value": [{"v": "EVA"}, {"v": "天鹰战士"}]}
              ],
              "volumes": 0,
              "eps": 26,
              "total_episodes": 26,
              "rating": {"total": 100, "count": {"10": 50}, "score": 8.5},
              "collection": {"doing": 1, "on_hold": 2, "dropped": 3, "wish": 4, "collect": 5},
              "tags": [
                {"name": "EVA", "count": 10311, "total_cont": 0},
                {"name": "庵野秀明", "count": 5000}
              ],
              "meta_tags": ["科幻", "TV"],
              "series": false,
              "some_unknown_future_field": {"a": 1}
            }
        """.trimIndent()

        val d = json.decodeFromString<BangumiDetails>(payload)

        assertEquals("新世纪福音战士", d.nameCn)
        assertEquals(8.5, d.rating.score, 0.0001)
        assertEquals(2, d.infobox.size)
        assertEquals("中文名", d.infobox[0].key)
        assertEquals(listOf("EVA", "庵野秀明"), d.tags.map { it.name })
        assertEquals(10311, d.tags[0].count)
        assertEquals(listOf("科幻", "TV"), d.metaTags)
    }
}

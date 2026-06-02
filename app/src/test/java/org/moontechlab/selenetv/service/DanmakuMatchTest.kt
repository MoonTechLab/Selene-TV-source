package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.moontechlab.selenetv.service.danmaku.DanmakuEpisode
import org.moontechlab.selenetv.service.danmaku.DanmakuMedia

/**
 * DanmakuService.pickBestMatch 的标题匹配回归。
 * 真实事故：剧名「主角」误中腾讯「《主角》原著超前看」等派生内容（弹幕仅 2 条）。
 */
class DanmakuMatchTest {

    private fun media(title: String, id: String = title) =
        DanmakuMedia(provider = "t", mediaId = id, title = title)

    @Test
    fun `主角 不应误中派生内容`() {
        // 腾讯 search「主角」实际返回的全是派生节目，无本编 → 应判未命中
        val candidates = listOf(
            media("《主角》原著超前看"),
            media("主角 名场面"),
            media("主角：超前大赏"),
            media("主角 超前看"),
            media("每个人都是《主角》"),
        )
        assertNull(DanmakuService.pickBestMatch("主角", candidates))
    }

    @Test
    fun `归一完全一致命中`() {
        val c = listOf(media("庆余年", "qyn"), media("庆余年 第二季", "qyn2"))
        assertEquals("qyn", DanmakuService.pickBestMatch("庆余年", c)?.mediaId)
    }

    @Test
    fun `书名号空格标点差异仍命中`() {
        val c = listOf(media("《白夜追凶》", "bz"))
        assertEquals("bz", DanmakuService.pickBestMatch("白夜追凶", c)?.mediaId)
    }

    @Test
    fun `去标点季度归一后一致可命中`() {
        val c = listOf(media("凡人修仙传 第二季", "frx2"))
        assertEquals("frx2", DanmakuService.pickBestMatch("凡人修仙传第二季", c)?.mediaId)
    }

    @Test
    fun `篇章后缀_基础剧名为前缀可命中`() {
        // 跨平台命名：记录名带篇章「凡人修仙传：风起天南」，B站只叫「凡人修仙传」→ 前缀包含同季应命中
        val c = listOf(media("凡人修仙传", "bili"))
        assertEquals("bili", DanmakuService.pickBestMatch("凡人修仙传：风起天南", c)?.mediaId)
    }

    @Test
    fun `派生内容_解说有声剧PV单集_被黑名单剔除`() {
        // 前缀规则放宽后，无精确正片时这些派生不应被误选
        val c = listOf(
            media("凡人修仙传 解说版"),
            media("凡人修仙传 多人有声剧"),
            media("凡人修仙传战斗PV"),
            media("凡人修仙传 单集回顾"),
        )
        assertNull(DanmakuService.pickBestMatch("凡人修仙传", c))
    }

    @Test
    fun `正片不被派生黑名单误伤`() {
        // 黑名单不应误伤正常正片名
        val c = listOf(media("凡人修仙传", "ok"))
        assertEquals("ok", DanmakuService.pickBestMatch("凡人修仙传", c)?.mediaId)
    }

    @Test
    fun `前缀过短不命中_主角不中女主角`() {
        // ≥4 字守卫：避免短名「主角」前缀误中「主角XXX」这类派生
        val c = listOf(media("主角的逆袭人生"))
        assertNull(DanmakuService.pickBestMatch("主角", c))
    }

    @Test
    fun `季度不一致不应命中`() {
        // S1 剧名不该误中只有 S2 的候选（季度识别生效）
        val c = listOf(media("凡人修仙传 第二季", "frx2"))
        assertNull(DanmakuService.pickBestMatch("凡人修仙传", c))
    }

    @Test
    fun `模糊近似可命中（繁简一字差）`() {
        val c = listOf(media("命中注定我愛你", "fan"))
        assertEquals("fan", DanmakuService.pickBestMatch("命中注定我爱你", c)?.mediaId)
    }

    @Test
    fun `季度识别_各格式`() {
        assertEquals(2, DanmakuService.seasonFromTitle("庆余年 第二季"))
        assertEquals(2, DanmakuService.seasonFromTitle("斗罗大陆2"))
        assertEquals(3, DanmakuService.seasonFromTitle("Season 3 something"))
        assertEquals(3, DanmakuService.seasonFromTitle("刀剑神域 III"))
        assertEquals(1, DanmakuService.seasonFromTitle("主角"))
        assertEquals(1, DanmakuService.seasonFromTitle("葛秃子传奇"))
    }

    @Test
    fun `完全一致优先于季标候选`() {
        val c = listOf(media("斗罗大陆 第二季", "s2"), media("斗罗大陆", "s1"))
        assertEquals("s1", DanmakuService.pickBestMatch("斗罗大陆", c)?.mediaId)
    }

    @Test
    fun `空候选与空剧名返回 null`() {
        assertNull(DanmakuService.pickBestMatch("主角", emptyList()))
        assertNull(DanmakuService.pickBestMatch("", listOf(media("主角"))))
    }

    private fun ep(title: String, idx: Int) =
        DanmakuEpisode(provider = "t", episodeId = "e$idx", title = title, episodeIndex = idx)

    @Test
    fun `垃圾分集被剔除`() {
        val eps = listOf(
            ep("预告：先导预告", 1), ep("第1集", 2), ep("第2集", 3),
            ep("花絮合集", 4), ep("幕后纪录片", 5),
        )
        val kept = DanmakuService.filterJunkEpisodes(eps)
        assertEquals(listOf("第1集", "第2集"), kept.map { it.title })
    }

    @Test
    fun `全为垃圾时保留原列表防误杀`() {
        val eps = listOf(ep("预告1", 1), ep("花絮2", 2))
        assertEquals(2, DanmakuService.filterJunkEpisodes(eps).size)
    }

    @Test
    fun `正常分集不被误伤`() {
        val eps = listOf(ep("第1集", 1), ep("第2集 双面", 2), ep("大结局", 3))
        assertEquals(3, DanmakuService.filterJunkEpisodes(eps).size)
    }
}

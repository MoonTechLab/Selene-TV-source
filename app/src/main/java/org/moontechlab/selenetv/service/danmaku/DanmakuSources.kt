package org.moontechlab.selenetv.service.danmaku

/**
 * 弹幕源注册表。Phase 2 多源聚合的静态清单：每个源 = (key, 显示名, 默认是否启用, 原生实现工厂)。
 *
 * 各源为原生 Kotlin 实现（已移除 Rhino/JS 方案）；清单只收已逆向验证可出弹幕的源（全部默认启用）。
 * 注：一次只用一个源渲染（见 [DanmakuService.fetchChunk]），故启用多个源只是扩大候选，不会叠加上屏。
 */
object DanmakuSources {

    data class Def(
        val key: String,
        val display: String,
        val defaultEnabled: Boolean,
        val factory: () -> DanmakuSource,
    )

    val ALL: List<Def> = listOf(
        Def("bilibili", "哔哩哔哩", true, ::BilibiliDanmakuSource),
        Def("tencent", "腾讯视频", true, ::TencentDanmakuSource),
        Def("iqiyi", "爱奇艺", true, ::IqiyiDanmakuSource),
        Def("youku", "优酷", true, ::YoukuDanmakuSource),
        Def("mgtv", "芒果TV", true, ::MgtvDanmakuSource),
        Def("letv", "乐视视频", true, ::LetvDanmakuSource),
    )

    val byKey: Map<String, Def> = ALL.associateBy { it.key }

    val defaultEnabledKeys: Set<String> = ALL.filter { it.defaultEnabled }.map { it.key }.toSet()

    fun displayOf(key: String): String = byKey[key]?.display ?: key
}

package org.moontechlab.selenetv.service.danmaku

/**
 * 弹幕源注册表。Phase 2 多源聚合的静态清单：每个源 = (key, 显示名, 脚本 asset, 默认是否启用)。
 *
 * 脚本当前从 assets 本地加载（Phase 3 改远程下发清单时，本表换成远程拉取即可，调用方不变）。
 * 清单只收已逆向验证可出弹幕的源（全部默认启用）；best-effort/骨架占位源已移除。
 * 注：一次只用一个源渲染（见 [DanmakuService.fetchComments]），故启用多个源只是扩大候选，不会叠加上屏。
 */
object DanmakuSources {

    data class Def(
        val key: String,
        val display: String,
        val asset: String,
        val defaultEnabled: Boolean,
    )

    val ALL: List<Def> = listOf(
        Def("bilibili", "哔哩哔哩", "danmaku/bilibili.js", true),
        Def("tencent", "腾讯视频", "danmaku/tencent.js", true),
        Def("iqiyi", "爱奇艺", "danmaku/iqiyi.js", true),
        Def("youku", "优酷", "danmaku/youku.js", true),
        Def("mgtv", "芒果TV", "danmaku/mgtv.js", true),
        Def("sohu", "搜狐视频", "danmaku/sohu.js", true),
        Def("letv", "乐视视频", "danmaku/letv.js", true),
        Def("xigua", "西瓜视频", "danmaku/xigua.js", true),
        Def("renren", "人人视频", "danmaku/renren.js", true),
        Def("hanjutv", "韩剧TV", "danmaku/hanjutv.js", true),
    )

    val byKey: Map<String, Def> = ALL.associateBy { it.key }

    val defaultEnabledKeys: Set<String> = ALL.filter { it.defaultEnabled }.map { it.key }.toSet()

    fun displayOf(key: String): String = byKey[key]?.display ?: key
}

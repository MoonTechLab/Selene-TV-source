package org.moontechlab.selenetv.service.danmaku

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import org.moontechlab.selenetv.model.DanmakuComment

/**
 * 由远程下发的 JS 脚本驱动的弹幕源（Phase 1：脚本从 assets 本地加载，Phase 2 改为远程清单）。
 *
 * 脚本约定实现三个函数，各返回 JSON 字符串：
 *   search(keyword)      -> [DanmakuMedia]
 *   getEpisodes(mediaId) -> [DanmakuEpisode]
 *   getComments(epId)    -> [DanmakuComment]
 * 抓取策略全在脚本里，native 只通过 [DanmakuHostBridge] 提供 HTTP 与 protobuf 解码原语。
 */
class RhinoDanmakuSource(
    context: Context,
    private val scriptAsset: String,
    override val providerName: String,
) : DanmakuSource {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val engine: JsDanmakuEngine? by lazy {
        runCatching {
            val src = appContext.assets.open(scriptAsset).bufferedReader().use { it.readText() }
            JsDanmakuEngine(src, scriptAsset, DanmakuHostBridge())
        }.onFailure { Log.w("DanmakuJS", "加载脚本 $scriptAsset 失败: ${it.message}") }.getOrNull()
    }

    override fun search(keyword: String): List<DanmakuMedia> =
        decode(engine?.call("search", keyword))

    override fun getEpisodes(mediaId: String): List<DanmakuEpisode> =
        decode(engine?.call("getEpisodes", mediaId))

    override fun getComments(episodeId: String): List<DanmakuComment> =
        decode(engine?.call("getComments", episodeId))

    private inline fun <reified T> decode(jsonStr: String?): List<T> =
        if (jsonStr.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<T>>(jsonStr) }.getOrDefault(emptyList())
}

package org.moontechlab.selenetv.service.danmaku

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.MediaHttp
import java.util.zip.Inflater

/**
 * B站弹幕源（Phase 0 原生实现）。端点对齐作者 l429609201/emby-plugin-danmu 的 BilibiliApi（已实测可用）：
 *   search:   /x/web-interface/search/all/v2?keyword=..&platform=pc&duration=0&order=totalrank（**非 wbi**，需先建 buvid3 会话）
 *             解析 video(UGC, mediaId="bv:"+bvid) 与 media_bangumi/media_ft(PGC, mediaId="ss"+season_id)
 *   episodes: PGC → /pgc/view/web/ep/list?season_id=..；UGC → /x/player/pagelist?bvid=..（每分P一个 cid）
 *   comments: /x/v1/dm/list.so?oid={cid} —— 返回 **deflate 压缩的 XML**，需 inflate(nowrap) 后按 <d p=..> 解析
 *
 * 注意：PGC(番剧/影视) 搜索结果受 B站地域版权限制——海外 IP 通常返回空，仅 UGC video 可用；
 * 国内 IP 两者皆可。best-effort：任何失败返回空，绝不抛异常。
 */
class BilibiliDanmakuSource : DanmakuSource {
    override val providerName = "bilibili"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = MediaHttp.httpClient

    private companion object {
        const val TAG = "DanmakuBili"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com/"
        const val MAX_SEGMENTS = 100   // 每段约 6 分钟 → 上限 ~10 小时，防失控
    }

    @Volatile private var buvidCookie: String? = null

    // ───────────────────────── search ─────────────────────────

    override fun search(keyword: String): List<DanmakuMedia> {
        val out = ArrayList<DanmakuMedia>()
        runCatching {
            val url = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=" +
                enc(keyword) + "&platform=pc&duration=0&order=totalrank"
            val root = json.parseToJsonElement(httpGet(url)).jsonObject
            val groups = (root["data"] as? JsonObject)?.get("result") as? JsonArray ?: JsonArray(emptyList())
            // PGC（番剧/影视）优先，UGC（video）兜底——海外 PGC 常空，UGC 仍能出弹幕。
            val pgc = ArrayList<DanmakuMedia>()
            val ugc = ArrayList<DanmakuMedia>()
            for (g in groups) {
                val go = g as? JsonObject ?: continue
                val type = (go["result_type"] as? JsonPrimitive)?.content ?: continue
                val items = go["data"] as? JsonArray ?: continue
                when (type) {
                    "media_bangumi", "media_ft" -> items.forEach { it ->
                        val o = it.jsonObject
                        val seasonId = (o["season_id"] as? JsonPrimitive)?.intOrNull ?: return@forEach
                        pgc += DanmakuMedia(
                            provider = providerName,
                            mediaId = "ss$seasonId",
                            title = cleanTitle((o["title"] as? JsonPrimitive)?.content),
                            type = if (type == "media_ft") "movie" else "tvseries",
                            episodeCount = (o["ep_size"] as? JsonPrimitive)?.intOrNull,
                        )
                    }
                    "video" -> items.forEach { it ->
                        val o = it.jsonObject
                        val bvid = (o["bvid"] as? JsonPrimitive)?.content ?: return@forEach
                        ugc += DanmakuMedia(
                            provider = providerName,
                            mediaId = "bv:$bvid",
                            title = cleanTitle((o["title"] as? JsonPrimitive)?.content),
                            type = "video",
                        )
                    }
                }
            }
            out += pgc
            out += ugc
        }.onFailure { Log.w(TAG, "search '$keyword' failed: ${it.message}") }
        Log.i(TAG, "search '$keyword' -> ${out.size} 条（PGC+UGC）")
        return out
    }

    // ──────────────────────── getEpisodes ────────────────────────

    override fun getEpisodes(mediaId: String): List<DanmakuEpisode> = when {
        mediaId.startsWith("ss") -> pgcEpisodes(mediaId.removePrefix("ss").toIntOrNull())
        mediaId.startsWith("bv:") -> ugcEpisodes(mediaId.removePrefix("bv:"))
        else -> emptyList()
    }

    /** PGC：/pgc/view/web/ep/list?season_id=.. → episodes[].cid。 */
    private fun pgcEpisodes(seasonId: Int?): List<DanmakuEpisode> {
        seasonId ?: return emptyList()
        return runCatching {
            val url = "https://api.bilibili.com/pgc/view/web/ep/list?season_id=$seasonId"
            val root = json.parseToJsonElement(httpGet(url)).jsonObject
            val episodes = (root["result"] as? JsonObject)?.get("episodes") as? JsonArray ?: return emptyList()
            episodes.mapIndexedNotNull { idx, el ->
                val o = el.jsonObject
                val cid = (o["cid"] as? JsonPrimitive)?.longOrNull ?: return@mapIndexedNotNull null
                val title = (o["long_title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: (o["title"] as? JsonPrimitive)?.content ?: "第${idx + 1}集"
                DanmakuEpisode(providerName, cid.toString(), title, idx + 1)
            }
        }.getOrDefault(emptyList()).also { Log.i(TAG, "pgcEpisodes ss$seasonId -> ${it.size}") }
    }

    /** UGC：/x/player/pagelist?bvid=.. → 每分P一个 cid。 */
    private fun ugcEpisodes(bvid: String): List<DanmakuEpisode> = runCatching {
        val url = "https://api.bilibili.com/x/player/pagelist?bvid=$bvid"
        val root = json.parseToJsonElement(httpGet(url)).jsonObject
        val pages = root["data"] as? JsonArray ?: return emptyList()
        pages.mapIndexedNotNull { idx, el ->
            val o = el.jsonObject
            val cid = (o["cid"] as? JsonPrimitive)?.longOrNull ?: return@mapIndexedNotNull null
            val title = (o["part"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "P${idx + 1}"
            DanmakuEpisode(providerName, cid.toString(), title, idx + 1)
        }
    }.getOrDefault(emptyList()).also { Log.i(TAG, "ugcEpisodes $bvid -> ${it.size}") }

    // ──────────────────────── getComments ────────────────────────

    /**
     * 取全量弹幕。主路径：新版分段 protobuf `x/v2/dm/web/seg.so?type=1&oid=cid&segment_index=N`
     * （每段约 6 分钟，逐段累加直到空段）。兜底：旧版 `x/v1/dm/list.so`（deflate XML）。
     */
    override fun getComments(episodeId: String): List<DanmakuComment> {
        val cid = episodeId.toLongOrNull() ?: return emptyList()
        val all = ArrayList<DanmakuComment>()
        runCatching {
            var seg = 1
            while (seg <= MAX_SEGMENTS) {
                val bytes = httpGetBytes(
                    "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=$seg"
                )
                if (bytes.isEmpty()) break          // 段越界 → 非 2xx → 空字节 → 结束
                val part = BiliDmProto.parseSegment(bytes)
                if (part.isEmpty()) break
                all += part
                seg++
            }
        }
        if (all.isEmpty()) {
            // seg.so 偶发为空（部分内容需登录态）→ 退回旧版 XML
            runCatching {
                val xml = inflateDanmaku(httpGetBytes("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid"))
                all += BiliDanmaku.parseXml(xml)
            }
        }
        Log.i(TAG, "getComments cid=$cid -> ${all.size} 条弹幕")
        return all
    }

    /** dm/list.so 是 raw deflate（nowrap）。失败则退化按 zlib/明文尝试。 */
    private fun inflateDanmaku(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // 1) raw deflate
        runCatching {
            val inflater = Inflater(true)
            inflater.setInput(bytes)
            val buf = ByteArray(1 shl 16)
            val sb = StringBuilder()
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            inflater.end()
            val s = sb.toString()
            if (s.contains("<d ")) return s
        }
        // 2) 明文（少数情况服务端直接返回未压缩 XML）
        val plain = String(bytes, Charsets.UTF_8)
        return if (plain.contains("<d ")) plain else ""
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 建 buvid3 会话（搜索接口要求）：访问主站 + spi，缓存 cookie。 */
    private fun ensureBuvid(): String {
        buvidCookie?.let { return it }
        val c = runCatching {
            // 先摸主站拿基础 cookie（部分情况下 spi 需要）
            runCatching { httpGetRaw("https://www.bilibili.com") }
            val root = json.parseToJsonElement(httpGetRaw("https://api.bilibili.com/x/frontend/finger/spi")).jsonObject
            val data = root["data"]?.jsonObject
            val b3 = data?.get("b_3")?.jsonPrimitive?.content
            val b4 = data?.get("b_4")?.jsonPrimitive?.content
            listOfNotNull(b3?.let { "buvid3=$it" }, b4?.let { "buvid4=$it" }).joinToString("; ")
        }.getOrDefault("")
        buvidCookie = c
        return c
    }

    private fun httpGet(url: String): String =
        String(request(url, ensureBuvid().takeIf { it.isNotBlank() }), Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray =
        request(url, ensureBuvid().takeIf { it.isNotBlank() })

    private fun httpGetRaw(url: String): String = String(request(url, null), Charsets.UTF_8)

    private fun request(url: String, cookie: String?): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .apply { cookie?.let { header("Cookie", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.bytes() ?: ByteArray(0) else ByteArray(0)
        }
    }

    private fun cleanTitle(raw: String?): String =
        raw?.replace(Regex("<[^>]+>"), "")?.replace("&amp;", "&") ?: ""

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

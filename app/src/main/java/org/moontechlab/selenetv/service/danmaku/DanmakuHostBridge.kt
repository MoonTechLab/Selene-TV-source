package org.moontechlab.selenetv.service.danmaku

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 弹幕脚本的宿主桥接：把「不变的原语」（HTTP、protobuf 解码）以 native 方法暴露给 JS。
 * 每个弹幕源的「易变策略」（端点、参数、分段循环、响应→统一模型映射）都写在 JS 脚本里。
 *
 * 方法经 Rhino LiveConnect 反射调用——签名只用 String，返回值也只用 String（含 JSON），
 * 避免 JS↔Java 复杂类型编解码。proguard 已 keep 本类全部方法。
 */
class DanmakuHostBridge(private val client: OkHttpClient = MediaHttp.httpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com/"
    }

    /** GET 文本（JSON/HTML/XML）。headersJson 为 JSON 对象字符串或空。失败返回空串。 */
    fun httpGet(url: String, headersJson: String?): String =
        runCatching { String(requestBytes(url, headersJson), Charsets.UTF_8) }.getOrDefault("")

    /**
     * 取一段 B站新版 protobuf 弹幕（seg.so），native 解码后返回统一弹幕的 JSON 数组：
     * `[{"timeMs":..,"mode":..,"color":..,"content":".."}, ...]`。空段/失败返回 `[]`。
     * 这是「protobuf 解码原语」——保持 native，JS 只负责拼 URL 与分段循环。
     */
    fun fetchDanmakuSeg(url: String, headersJson: String?): String = runCatching {
        val bytes = requestBytes(url, headersJson)
        val list = BiliDmProto.parseSegment(bytes)
        json.encodeToString(list)
    }.getOrDefault("[]")

    /**
     * 兜底：旧版 deflate XML 弹幕（list.so）。native 取字节 + inflate + 解析，返回统一弹幕 JSON 数组。
     * seg.so 偶发为空（部分内容需登录态）时由脚本调用。
     */
    fun fetchDanmakuXml(url: String, headersJson: String?): String = runCatching {
        val xml = inflateRaw(requestBytes(url, headersJson))
        json.encodeToString(BiliDanmaku.parseXml(xml))
    }.getOrDefault("[]")

    /** 供 JS 打日志（TAG: DanmakuJS）。 */
    fun log(msg: String) {
        Log.i("DanmakuJS", msg)
    }

    /** dm/list.so 是 raw deflate（nowrap）；失败退化按明文。 */
    private fun inflateRaw(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        runCatching {
            val inflater = java.util.zip.Inflater(true)
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
        val plain = String(bytes, Charsets.UTF_8)
        return if (plain.contains("<d ")) plain else ""
    }

    private fun requestBytes(url: String, headersJson: String?): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (!headersJson.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(headersJson)
                obj.keys().forEach { k -> builder.header(k, obj.optString(k)) }
            }
        }
        client.newCall(builder.build()).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.bytes() ?: ByteArray(0) else ByteArray(0)
        }
    }
}

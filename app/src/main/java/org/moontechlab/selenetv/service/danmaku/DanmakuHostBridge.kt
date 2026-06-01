package org.moontechlab.selenetv.service.danmaku

import android.util.Base64
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.selenetv.service.MediaHttp
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 弹幕脚本的宿主桥接：把所有「不变的原语」（HTTP / 解压 / 哈希 / AES / protobuf）以 native 方法
 * 暴露给 JS。各弹幕源的「易变策略」（端点、参数、签名、分段、响应→统一模型映射）都写在 JS 脚本里。
 *
 * 方法经 Rhino 手动注册调用（见 [JsDanmakuEngine]）——签名/返回值只用 String（含 JSON / base64），
 * 避免 JS↔Java 复杂类型编解码。带进程内 CookieJar（优酷 mtop 的 _m_h5_tk 需 cookie 往返）。
 */
class DanmakuHostBridge {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 专用客户端：复用项目 trust-all 配置，附加内存 CookieJar（不影响 App 全局客户端）。
    private val client: OkHttpClient = MediaHttp.httpClient.newBuilder()
        .cookieJar(MemoryCookieJar())
        .build()

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    // ───────────────────────── HTTP ─────────────────────────

    /** GET，UTF-8 文本。非 2xx 也返回 body（很多源用 404/错误体做终止信号）。 */
    fun httpGet(url: String, headersJson: String?): String =
        String(requestBytes("GET", url, null, headersJson), Charsets.UTF_8)

    /** GET，GBK 文本（搜狐/乐视等 GBK 页面）。 */
    fun httpGetGbk(url: String, headersJson: String?): String =
        String(requestBytes("GET", url, null, headersJson), Charset.forName("GBK"))

    /** POST，UTF-8 文本。 */
    fun httpPost(url: String, body: String?, headersJson: String?): String =
        String(requestBytes("POST", url, body, headersJson), Charsets.UTF_8)

    /** GET，返回二进制的 base64（protobuf / zlib / gzip 等）。 */
    fun httpGetBytes(url: String, headersJson: String?): String =
        Base64.encodeToString(requestBytes("GET", url, null, headersJson), Base64.NO_WRAP)

    /** GET，返回完整响应 JSON：{status, body, headers}（headers 为对象，set-cookie 合并为字符串）。 */
    fun httpGetFull(url: String, headersJson: String?): String = requestFull("GET", url, null, headersJson)

    fun httpPostFull(url: String, body: String?, headersJson: String?): String =
        requestFull("POST", url, body, headersJson)

    /** 读取 CookieJar 中某 cookie 当前值（优酷取 _m_h5_tk 算签名用）。 */
    fun cookie(url: String, name: String): String = runCatching {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        (client.cookieJar as? MemoryCookieJar)?.loadForRequest(httpUrl)
            ?.firstOrNull { it.name == name }?.value ?: ""
    }.getOrDefault("")

    // ─────────────────── 弹幕专用（B站，已存在） ───────────────────

    /** B站 seg.so protobuf → 统一弹幕 JSON 数组。 */
    fun fetchDanmakuSeg(url: String, headersJson: String?): String = runCatching {
        json.encodeToString(BiliDmProto.parseSegment(requestBytes("GET", url, null, headersJson)))
    }.getOrDefault("[]")

    /** B站 list.so deflate XML → 统一弹幕 JSON 数组。 */
    fun fetchDanmakuXml(url: String, headersJson: String?): String = runCatching {
        val xml = String(DanmakuPrimitives.inflate(requestBytes("GET", url, null, headersJson), raw = true), Charsets.UTF_8)
        val parsed = if (xml.contains("<d ")) xml else String(requestBytes("GET", url, null, headersJson), Charsets.UTF_8)
        json.encodeToString(BiliDanmaku.parseXml(parsed))
    }.getOrDefault("[]")

    // ───────────────────────── 解压 ─────────────────────────

    /** raw=true → raw deflate（nowrap）；raw=false → zlib 包裹。入参/出参均按 base64→UTF-8。 */
    fun inflate(b64: String, raw: Boolean): String = runCatching {
        String(DanmakuPrimitives.inflate(Base64.decode(b64, Base64.DEFAULT), raw), Charsets.UTF_8)
    }.getOrDefault("")

    fun gunzip(b64: String): String = runCatching {
        String(DanmakuPrimitives.gunzip(Base64.decode(b64, Base64.DEFAULT)), Charsets.UTF_8)
    }.getOrDefault("")

    // ───────────────────────── 哈希 / 加密 ─────────────────────────

    fun md5(s: String): String = DanmakuPrimitives.md5(s)
    fun sha1(s: String): String = DanmakuPrimitives.sha1(s)
    fun hmac(algo: String, msg: String, key: String): String = DanmakuPrimitives.hmac(algo, msg, key)

    /** HMAC → Base64（人人 x-ca-sign 等）。 */
    fun hmacBase64(algo: String, msg: String, key: String): String =
        Base64.encodeToString(DanmakuPrimitives.hmacRaw(algo, msg, key), Base64.NO_WRAP)

    /** AES-ECB-PKCS7 解密。b64Data=密文 base64，keyUtf8=密钥明文。返回明文 UTF-8。 */
    fun aesEcbDecrypt(b64Data: String, keyUtf8: String): String = runCatching {
        String(DanmakuPrimitives.aesEcbDecrypt(Base64.decode(b64Data, Base64.DEFAULT), keyUtf8.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }.getOrDefault("")

    fun aesCbcDecrypt(b64Data: String, keyUtf8: String, ivUtf8: String): String = runCatching {
        String(
            DanmakuPrimitives.aesCbcDecrypt(
                Base64.decode(b64Data, Base64.DEFAULT),
                keyUtf8.toByteArray(Charsets.UTF_8),
                ivUtf8.toByteArray(Charsets.UTF_8),
            ),
            Charsets.UTF_8,
        )
    }.getOrDefault("")

    /** CBC 解密但不去填充（非标准末尾填充由 JS 自行 strip，如韩剧tv）。返回 UTF-8。 */
    fun aesCbcDecryptNoPad(b64Data: String, keyUtf8: String, ivUtf8: String): String = runCatching {
        String(
            DanmakuPrimitives.aesCbcDecryptNoPad(
                Base64.decode(b64Data, Base64.DEFAULT),
                keyUtf8.toByteArray(Charsets.UTF_8),
                ivUtf8.toByteArray(Charsets.UTF_8),
            ),
            Charsets.UTF_8,
        )
    }.getOrDefault("")

    /** CBC 加密（明文 UTF-8 → 密文 base64，如韩剧tv 请求头派生）。 */
    fun aesCbcEncrypt(plainUtf8: String, keyUtf8: String, ivUtf8: String): String = runCatching {
        Base64.encodeToString(
            DanmakuPrimitives.aesCbcEncrypt(
                plainUtf8.toByteArray(Charsets.UTF_8),
                keyUtf8.toByteArray(Charsets.UTF_8),
                ivUtf8.toByteArray(Charsets.UTF_8),
            ),
            Base64.NO_WRAP,
        )
    }.getOrDefault("")

    fun b64decode(s: String): String = runCatching {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault("")

    fun b64encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** 通用 protobuf 解码 → JSON：字段按编号 {"1":..}，length-delimited 优先当嵌套消息、否则当字符串；repeated → 数组。 */
    fun protoDecode(b64: String): String = runCatching {
        ProtoWalker.decode(Base64.decode(b64, Base64.DEFAULT)).toString()
    }.getOrDefault("{}")

    fun log(msg: String) = Log.i("DanmakuJS", msg)

    // ───────────────────────── 内部 ─────────────────────────

    private fun buildRequest(method: String, url: String, body: String?, headersJson: String?): Request {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (!headersJson.isNullOrBlank()) runCatching {
            val obj = JSONObject(headersJson)
            obj.keys().forEach { k -> builder.header(k, obj.optString(k)) }
        }
        // Content-Type 若在 headersJson 里已设，OkHttp 用它；这里 mediaType 传 null。
        if (method == "POST") builder.post((body ?: "").toRequestBody(null))
        return builder.build()
    }

    private fun requestBytes(method: String, url: String, body: String?, headersJson: String?): ByteArray =
        runCatching {
            client.newCall(buildRequest(method, url, body, headersJson)).execute().use { resp ->
                resp.body?.bytes() ?: ByteArray(0)
            }
        }.getOrDefault(ByteArray(0))

    private fun requestFull(method: String, url: String, body: String?, headersJson: String?): String =
        runCatching {
            client.newCall(buildRequest(method, url, body, headersJson)).execute().use { resp ->
                val headers = JSONObject()
                for (n in resp.headers.names()) {
                    val values = resp.headers.values(n)
                    headers.put(n.lowercase(), if (values.size == 1) values[0] else JSONArray(values))
                }
                JSONObject()
                    .put("status", resp.code)
                    .put("body", resp.body?.string() ?: "")
                    .put("headers", headers)
                    .toString()
            }
        }.getOrDefault("""{"status":0,"body":"","headers":{}}""")

    /** 极简进程内 CookieJar（按 host 存，覆盖同名）。 */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val map = store.getOrPut(url.host) { ConcurrentHashMap() }
            cookies.forEach { map[it.name] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val out = ArrayList<Cookie>()
            store.forEach { (h, map) -> if (host == h || host.endsWith(".$h") || h.endsWith(".$host")) out.addAll(map.values) }
            return out
        }
    }
}

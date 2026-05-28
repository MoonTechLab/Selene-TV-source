package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

object SubscriptionService {
    private const val TAG = "SubscriptionService"

    class SubscriptionException(message: String) : Exception(message)

    data class ParsedSubscription(
        val searchResources: List<SearchResource>,
        val liveSources: List<LiveSource>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 纯解析：Base58 解码后的 JSON 字符串 → ParsedSubscription。失败抛 SubscriptionException。 */
    fun parseSubscriptionJson(text: String): ParsedSubscription {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw SubscriptionException("订阅内容格式无效: ${e.message}")
        }

        val apiSiteMap = runCatching { root["api_site"]?.jsonObject }.getOrNull() ?: emptyMap()
        val livesMap = runCatching { root["lives"]?.jsonObject }.getOrNull() ?: emptyMap()

        val searchResources = apiSiteMap.entries.mapNotNull { (mapKey, value) ->
            try {
                val obj = value.jsonObject
                SearchResource(
                    key = obj["key"]?.jsonPrimitive?.contentOrNull ?: mapKey,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    api = obj["api"]?.jsonPrimitive?.contentOrNull ?: "",
                    detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
                    from = obj["from"]?.jsonPrimitive?.contentOrNull,
                    disabled = false,
                )
            } catch (_: Exception) {
                null
            }
        }

        val liveSources = livesMap.entries.mapNotNull { (mapKey, value) ->
            try {
                val obj = value.jsonObject
                LiveSource(
                    key = obj["key"]?.jsonPrimitive?.contentOrNull ?: mapKey,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    ua = obj["ua"]?.jsonPrimitive?.contentOrNull ?: "",
                    epg = obj["epg"]?.jsonPrimitive?.contentOrNull ?: "",
                    from = obj["from"]?.jsonPrimitive?.contentOrNull ?: "",
                    disabled = false,
                )
            } catch (_: Exception) {
                null
            }
        }

        if (searchResources.isEmpty() && liveSources.isEmpty()) {
            throw SubscriptionException("订阅内容无可用源")
        }

        return ParsedSubscription(searchResources, liveSources)
    }

    /**
     * 同步阻塞：HTTP GET 订阅 URL → Base58 解码 → JSON 解析 → 可达性校验。失败抛 SubscriptionException。
     * 必须在 Dispatchers.IO 调用。
     */
    fun fetchAndParse(url: String): ParsedSubscription {
        val body = httpGet(url)
        val decoded = try {
            org.moontechlab.selenetv.util.Base58.decode(body)
        } catch (e: Exception) {
            throw SubscriptionException("订阅内容格式无效: Base58 解码失败 (${e.message})")
        }
        val text = String(decoded, Charsets.UTF_8)
        return parseSubscriptionJson(text)
    }

    /**
     * 后台刷新：使用已存订阅 URL 重新拉取并仅覆盖 sources（不动用户数据、不动订阅 URL）。
     * 返回 true=成功，false=失败（已记录日志）。
     */
    suspend fun refreshAndPersist(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl() ?: return@withContext false
        runCatching { fetchAndParse(url) }.fold(
            onSuccess = { parsed ->
                // 防竞态：拉取可能耗时数秒；落盘前再确认订阅 URL 未被用户改动，避免用旧 URL 的源覆盖新订阅的源
                val currentUrl = org.moontechlab.selenetv.data.local.LocalStore.getSubscriptionUrl()
                if (currentUrl != url) {
                    Log.w(TAG, "刷新期间订阅 URL 已变更，丢弃旧拉取结果")
                    return@fold false
                }
                org.moontechlab.selenetv.data.local.LocalStore.saveSubscriptionSources(
                    parsed.searchResources,
                    parsed.liveSources,
                )
                LiveService.clearAllCache()
                true
            },
            onFailure = {
                Log.w(TAG, "刷新订阅失败: ${it::class.simpleName}: ${it.message}")
                false
            },
        )
    }

    private fun httpGet(url: String): String {
        val u = try {
            java.net.URL(url)
        } catch (e: java.net.MalformedURLException) {
            throw SubscriptionException("订阅链接格式无效: ${e.message}")
        }
        val scheme = u.protocol?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SubscriptionException("订阅链接协议不支持（仅 http/https）")
        }
        val conn = u.openConnection() as java.net.HttpURLConnection
        // 自签证书：沿用项目约定，HTTPS 时信任所有 + 跳过 hostname 校验
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = trustAllSslSocketFactory()
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        try {
            val code = conn.responseCode
            if (code != 200) throw SubscriptionException("获取订阅内容失败 ($code)")
            return conn.inputStream.use { input ->
                val capped = readBytesCapped(input, MAX_SUBSCRIPTION_BYTES)
                String(capped, Charsets.UTF_8).trim()
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private const val MAX_SUBSCRIPTION_BYTES = 1 * 1024 * 1024  // 1 MB 上限：防止恶意订阅 OOM

    /**
     * 读取流并限制最大字节数。超出 cap 抛 SubscriptionException。
     */
    private fun readBytesCapped(input: java.io.InputStream, cap: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(tmp)
            if (n < 0) break
            total += n
            if (total > cap) throw SubscriptionException("订阅内容超过 ${cap / 1024} KB 上限")
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, tm, java.security.SecureRandom())
        return ctx.socketFactory
    }
}

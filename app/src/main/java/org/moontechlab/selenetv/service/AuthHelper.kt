package org.moontechlab.selenetv.service

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 登录 HTTP 请求辅助：手动处理 POST 的 3xx 重定向。
 *
 * 为什么需要：`HttpURLConnection` 默认在 POST 上不跟随重定向，且跨 http/https
 * 也不跟随（即便 GET 也不跨协议）。MoonTV/LunaTV 服务端常常前置 Cloudflare
 * 或 Nginx 强制 HTTPS 重定向、或追加 trailing slash —— `/api/login` 因此永远拿到 301，
 * 表现为「登录失败但所有 GET 能用」。手动跟随并把最终 baseUrl 反写回去，让首次登录
 * 和后续 cookie 刷新都能落到正确的真实地址。
 *
 * 沿用项目「信任所有证书 + 跳过 hostname 校验」约定，便于用户自签的 HTTPS 部署。
 */
internal object AuthHelper {
    private const val TAG = "AuthHelper"
    private const val MAX_REDIRECTS = 3
    private const val LOGIN_PATH = "/api/login"

    data class LoginResult(
        val responseCode: Int,
        val cookies: String,
        val baseUrl: String,
    )

    /**
     * 同步阻塞：POST `${initialBaseUrl}/api/login`，手动跟随 3xx 重定向最多 [MAX_REDIRECTS] 跳。
     * 必须在 `Dispatchers.IO` 调用。`baseUrl` 会被重定向后的最终地址覆盖回传，调用方应据此覆写 prefs。
     */
    fun postLogin(initialBaseUrl: String, username: String, password: String): LoginResult {
        var loginUrl = initialBaseUrl.trimEnd('/') + LOGIN_PATH
        var baseUrl = initialBaseUrl.trimEnd('/')
        // 用 JSONObject 序列化，避免用户名/密码里的 " \ 等特殊字符破坏 JSON 结构
        val jsonBody = org.json.JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
        val accumulatedCookies = LinkedHashMap<String, String>()
        var lastCode = 0
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val u = try {
                URL(loginUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Bad login URL: $loginUrl (${e.message})")
                return LoginResult(lastCode, "", baseUrl)
            }
            val conn = u.openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = trustAllSslSocketFactory()
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (accumulatedCookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", accumulatedCookies.values.joinToString("; "))
            }
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            // 关闭自动跟随：自动跟随只覆盖 GET 同协议，POST/跨协议都不跟。统一手动处理才不漏。
            conn.instanceFollowRedirects = false
            try {
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                val code = conn.responseCode
                lastCode = code

                // 累积当前响应的 Set-Cookie：3xx 跳转上设置的 CSRF/session cookie 也需要带到下一跳。
                conn.headerFields["Set-Cookie"]?.forEach { raw ->
                    val pair = raw.split(";").firstOrNull() ?: return@forEach
                    val eq = pair.indexOf('=')
                    if (eq > 0) accumulatedCookies[pair.substring(0, eq)] = pair
                }

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    val target = resolveLoginRedirect(loginUrl, location)
                    if (target == null) {
                        Log.w(TAG, "Unfollowable redirect from $loginUrl (Location=$location)")
                        return LoginResult(code, "", baseUrl)
                    }
                    if (target.loginUrl == loginUrl) {
                        Log.w(TAG, "Redirect points back to itself: $loginUrl")
                        return LoginResult(code, "", baseUrl)
                    }
                    Log.d(TAG, "Following login redirect: $baseUrl -> ${target.baseUrl}")
                    loginUrl = target.loginUrl
                    baseUrl = target.baseUrl
                    hops++
                    continue
                }

                return LoginResult(
                    responseCode = code,
                    cookies = accumulatedCookies.values.joinToString("; "),
                    baseUrl = baseUrl,
                )
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "Exceeded $MAX_REDIRECTS redirects starting from $initialBaseUrl")
        return LoginResult(lastCode, "", baseUrl)
    }

    internal data class RedirectTarget(val loginUrl: String, val baseUrl: String)

    /**
     * 把 Location header 解析为新的 (loginUrl, baseUrl) 对。
     * - 相对地址按 [currentLoginUrl] 解析。
     * - 仅允许 http/https。
     * - **同主机校验**：拒绝跨 host 重定向，防止恶意服务端把凭据 POST 到攻击者的 host
     *   并把 baseUrl 永久篡改到攻击者地址。允许端口变化（兼容 80↔443 的 HTTP/HTTPS 升级）。
     * - **拒绝 HTTPS→HTTP 降级**：上来就是 HTTPS 的请求被引向明文 HTTP，凭据会以明文重发。
     * - 若 location 仍指向 /api/login（可能带尾斜杠），剥离该后缀作为新 baseUrl；
     *   否则保守用 scheme://authority 作为 baseUrl，loginUrl 用 location 全路径。
     * - location 为 null/空 / 非 http(s) / 跨 host / HTTPS→HTTP 降级 / 不可解析 → 返回 null。
     */
    internal fun resolveLoginRedirect(currentLoginUrl: String, locationHeader: String?): RedirectTarget? {
        if (locationHeader.isNullOrBlank()) return null
        val current = try { URL(currentLoginUrl) } catch (_: Exception) { return null }
        val resolved = try { URL(current, locationHeader) } catch (_: Exception) { return null }
        val scheme = resolved.protocol?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        // 拒绝跨 host：用 host（不含端口）做比较，允许 80↔443 隐式端口变更
        if (!resolved.host.equals(current.host, ignoreCase = true)) return null

        // 拒绝 HTTPS→HTTP 降级（攻击者注入 301 到明文 HTTP 即可裸眼读取凭据）
        if (current.protocol.equals("https", ignoreCase = true) && scheme == "http") return null

        val auth = "${resolved.protocol}://${resolved.authority}"
        val rawPath = resolved.path.orEmpty()
        val hadTrailingSlash = rawPath.endsWith("/")
        val normalized = rawPath.trimEnd('/')

        return if (normalized.endsWith(LOGIN_PATH)) {
            val newBase = auth + normalized.removeSuffix(LOGIN_PATH)
            val newLoginUrl = if (hadTrailingSlash) "$newBase$LOGIN_PATH/" else "$newBase$LOGIN_PATH"
            RedirectTarget(newLoginUrl, newBase)
        } else {
            // location 指向其他路径（少见，例如 /auth/login 或 /dashboard 的鉴权重定向）：信任 location 全 URL。
            RedirectTarget(resolved.toString(), auth)
        }
    }

    private fun trustAllSslSocketFactory(): SSLSocketFactory {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tm, SecureRandom())
        return ctx.socketFactory
    }
}

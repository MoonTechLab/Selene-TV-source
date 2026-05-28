package org.moontechlab.selenetv.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 应用数据服务
 * 管理用户登录信息、设置等持久化数据
 */
object AppDataService {
    private const val TAG = "AppDataService"
    private const val PREFS_NAME = "selene_tv_prefs"

    // SharedPreferences Keys
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_HAS_LOGIN = "has_login"
    private const val KEY_DOUBAN_DATA_SOURCE = "douban_data_source"
    private const val KEY_DOUBAN_IMAGE_SOURCE = "douban_image_source"
    private const val KEY_BANGUMI_DATA_SOURCE = "bangumi_data_source"
    private const val KEY_BANGUMI_IMAGE_SOURCE = "bangumi_image_source"
    private const val KEY_M3U8_PROXY_URL = "m3u8_proxy_url"
    private const val KEY_TMDB_API_KEY = "tmdb_api_key"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_PLAYER_TYPE = "player_type"
    private const val KEY_DECODE_MODE = "decode_mode"
    private const val KEY_SEARCH_ORIGIN = "search_origin"
    private const val KEY_IS_LOCAL_MODE = "is_local_mode"

    private lateinit var prefs: SharedPreferences

    // 当前会话的 token（每次启动重新生成）
    private var sessionToken: String = ""

    // 本地模式缓存（同步读，零阻塞）
    @Volatile
    private var isLocalModeCache: Boolean = false

    /**
     * 初始化服务，需要在 Application 中调用
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLocalModeCache = prefs.getBoolean(KEY_IS_LOCAL_MODE, false)
        // 每次启动生成新的 session token
        generateSessionToken()
    }
    
    /**
     * 生成随机 session token
     */
    private fun generateSessionToken() {
        sessionToken = java.util.UUID.randomUUID().toString().replace("-", "")
        Log.d(TAG, "Generated session token: $sessionToken")
    }
    
    /**
     * 获取当前会话 token
     */
    fun getSessionToken(): String = sessionToken

    // ==================== 用户数据相关 ====================

    /**
     * 保存用户登录信息
     */
    suspend fun saveUserData(
        serverUrl: String,
        username: String,
        password: String,
        cookies: String
    ) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putString(KEY_COOKIES, cookies)
            .putBoolean(KEY_HAS_LOGIN, true)
            .apply()
    }


    /**
     * 获取服务器地址
     */
    suspend fun getServerUrl(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_SERVER_URL, null)
    }

    /**
     * 获取用户名
     */
    suspend fun getUsername(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_USERNAME, null)
    }

    /**
     * 获取密码
     */
    suspend fun getPassword(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_PASSWORD, null)
    }

    /**
     * 获取 Cookies
     */
    suspend fun getCookies(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_COOKIES, null)
    }

    /**
     * 检查是否已登录
     */
    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_HAS_LOGIN, false)
    }

    /**
     * 设置登录状态
     */
    suspend fun setHasLogin(hasLogin: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_HAS_LOGIN, hasLogin).apply()
    }

    /**
     * 清除所有用户数据
     */
    suspend fun clearUserData() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_COOKIES)
            .remove(KEY_HAS_LOGIN)
            .apply()
    }

    /**
     * 只清除密码和 Cookies，保留服务器地址和用户名
     */
    suspend fun clearPasswordAndCookies() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_PASSWORD)
            .remove(KEY_COOKIES)
            .putBoolean(KEY_HAS_LOGIN, false)
            .apply()
    }

    /**
     * 用户数据模型
     */
    data class UserData(
        val serverUrl: String?,
        val username: String?,
        val password: String?,
        val cookies: String?,
        val hasLogin: Boolean
    )

    /**
     * 获取所有用户数据
     */
    suspend fun getAllUserData(): UserData = withContext(Dispatchers.IO) {
        UserData(
            serverUrl = prefs.getString(KEY_SERVER_URL, null),
            username = prefs.getString(KEY_USERNAME, null),
            password = prefs.getString(KEY_PASSWORD, null),
            cookies = prefs.getString(KEY_COOKIES, null),
            hasLogin = prefs.getBoolean(KEY_HAS_LOGIN, false)
        )
    }

    /**
     * 检查是否具有自动登录所需的所有字段
     */
    suspend fun hasAutoLoginData(): Boolean = withContext(Dispatchers.IO) {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)
        val username = prefs.getString(KEY_USERNAME, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        !serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()
    }

    // ==================== 本地模式 ====================

    /**
     * 保存本地模式开关；同步更新内存缓存。
     */
    suspend fun saveIsLocalMode(isLocalMode: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_IS_LOCAL_MODE, isLocalMode).apply()
        isLocalModeCache = isLocalMode
    }

    suspend fun isLocalMode(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_IS_LOCAL_MODE, false)
    }

    /** 同步读，零阻塞；`init()` 时预热缓存。 */
    fun isLocalModeSync(): Boolean = isLocalModeCache

    // ==================== 豆瓣数据源设置 ====================

    /**
     * 豆瓣数据源枚举
     */
    enum class DoubanDataSource(val key: String, val displayName: String) {
        DIRECT("direct", "直连"),
        CORS_PROXY("cors_proxy", "Cors Proxy By Zwei"),
        CDN_TENCENT("cdn_tencent", "豆瓣 CDN By CMLiussss（腾讯云）"),
        CDN_ALIYUN("cdn_aliyun", "豆瓣 CDN By CMLiussss（阿里云）");

        companion object {
            fun fromKey(key: String): DoubanDataSource =
                entries.find { it.key == key } ?: DIRECT

            fun fromDisplayName(displayName: String): DoubanDataSource =
                entries.find { it.displayName == displayName } ?: DIRECT
        }
    }

    /**
     * 豆瓣图片源枚举
     */
    enum class DoubanImageSource(val key: String, val displayName: String) {
        DIRECT("direct", "直连"),
        SERVER_PROXY("server_proxy", "服务器代理"),
        OFFICIAL_CDN("official_cdn", "豆瓣官方精品 CDN"),
        CDN_TENCENT("cdn_tencent", "豆瓣 CDN By CMLiussss（腾讯云）"),
        CDN_ALIYUN("cdn_aliyun", "豆瓣 CDN By CMLiussss（阿里云）");

        companion object {
            fun fromKey(key: String): DoubanImageSource =
                entries.find { it.key == key } ?: DIRECT

            fun fromDisplayName(displayName: String): DoubanImageSource =
                entries.find { it.displayName == displayName } ?: DIRECT
        }
    }

    /**
     * Bangumi 数据源枚举：除 DIRECT 外，其余在请求 bgm API 时将 host 整体替换。
     */
    enum class BangumiDataSource(val key: String, val displayName: String) {
        DIRECT("direct", "直连"),
        CDN_TENCENT("cdn_tencent", "Bangumi CDN By CMLiussss（腾讯云）"),
        CDN_ALIYUN("cdn_aliyun", "Bangumi CDN By CMLiussss（阿里云）"),
        CDN_SHINYA("cdn_shinya", "Bangumi CDN By Shinya（CF 优选）");

        companion object {
            fun fromKey(key: String): BangumiDataSource =
                entries.find { it.key == key } ?: DIRECT

            fun fromDisplayName(displayName: String): BangumiDataSource =
                entries.find { it.displayName == displayName } ?: DIRECT
        }
    }

    /**
     * Bangumi 图片源枚举。SERVER_PROXY 走自托管 /api/image-proxy，本地模式不可用。
     */
    enum class BangumiImageSource(val key: String, val displayName: String) {
        DIRECT("direct", "直连"),
        SERVER_PROXY("server_proxy", "服务器代理");

        companion object {
            fun fromKey(key: String): BangumiImageSource =
                entries.find { it.key == key } ?: DIRECT

            fun fromDisplayName(displayName: String): BangumiImageSource =
                entries.find { it.displayName == displayName } ?: DIRECT
        }
    }

    /** 播放器后端 */
    enum class PlayerType(val key: String, val displayName: String) {
        EXOPLAYER("exoplayer", "ExoPlayer"),
        MPV("mpv", "MPV");

        companion object {
            fun fromKey(key: String): PlayerType = entries.find { it.key == key } ?: EXOPLAYER
            fun fromDisplayName(displayName: String): PlayerType =
                entries.find { it.displayName == displayName } ?: EXOPLAYER
        }
    }

    /** 解码方式 */
    enum class DecodeMode(val key: String, val displayName: String) {
        HARDWARE("hardware", "硬解"),
        SOFTWARE("software", "软解");

        companion object {
            fun fromKey(key: String): DecodeMode = entries.find { it.key == key } ?: HARDWARE
            fun fromDisplayName(displayName: String): DecodeMode =
                entries.find { it.displayName == displayName } ?: HARDWARE
        }
    }

    /** 搜索发起方 */
    enum class SearchOrigin(val key: String, val displayName: String) {
        TV_DIRECT("tv_direct", "TV 直连源站"),
        SERVER_AGGREGATED("server", "服务端聚合搜索");

        companion object {
            fun fromKey(key: String): SearchOrigin = entries.find { it.key == key } ?: TV_DIRECT
            fun fromDisplayName(displayName: String): SearchOrigin =
                entries.find { it.displayName == displayName } ?: TV_DIRECT
        }
    }

    /**
     * 保存豆瓣数据源设置（通过显示名称）
     */
    suspend fun saveDoubanDataSource(displayName: String) = withContext(Dispatchers.IO) {
        val source = DoubanDataSource.fromDisplayName(displayName)
        prefs.edit().putString(KEY_DOUBAN_DATA_SOURCE, source.key).apply()
    }

    /**
     * 获取豆瓣数据源 Key
     */
    suspend fun getDoubanDataSourceKey(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_DOUBAN_DATA_SOURCE, DoubanDataSource.DIRECT.key)
            ?: DoubanDataSource.DIRECT.key
    }

    /**
     * 获取豆瓣数据源显示名称
     */
    suspend fun getDoubanDataSourceDisplayName(): String = withContext(Dispatchers.IO) {
        val key = prefs.getString(KEY_DOUBAN_DATA_SOURCE, DoubanDataSource.DIRECT.key)
            ?: DoubanDataSource.DIRECT.key
        DoubanDataSource.fromKey(key).displayName
    }

    /**
     * 保存豆瓣图片源设置（通过显示名称）
     */
    suspend fun saveDoubanImageSource(displayName: String) = withContext(Dispatchers.IO) {
        val source = DoubanImageSource.fromDisplayName(displayName)
        prefs.edit().putString(KEY_DOUBAN_IMAGE_SOURCE, source.key).apply()
    }

    /**
     * 获取豆瓣图片源 Key
     */
    suspend fun getDoubanImageSourceKey(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_DOUBAN_IMAGE_SOURCE, DoubanImageSource.DIRECT.key)
            ?: DoubanImageSource.DIRECT.key
    }

    /**
     * 获取豆瓣图片源显示名称
     */
    suspend fun getDoubanImageSourceDisplayName(): String = withContext(Dispatchers.IO) {
        val key = prefs.getString(KEY_DOUBAN_IMAGE_SOURCE, DoubanImageSource.DIRECT.key)
            ?: DoubanImageSource.DIRECT.key
        DoubanImageSource.fromKey(key).displayName
    }

    /**
     * 保存 Bangumi 数据源（通过显示名称）
     */
    suspend fun saveBangumiDataSource(displayName: String) = withContext(Dispatchers.IO) {
        val source = BangumiDataSource.fromDisplayName(displayName)
        prefs.edit().putString(KEY_BANGUMI_DATA_SOURCE, source.key).apply()
    }

    suspend fun getBangumiDataSourceDisplayName(): String = withContext(Dispatchers.IO) {
        val key = prefs.getString(KEY_BANGUMI_DATA_SOURCE, BangumiDataSource.DIRECT.key)
            ?: BangumiDataSource.DIRECT.key
        BangumiDataSource.fromKey(key).displayName
    }

    /**
     * 保存 Bangumi 图片源（通过显示名称）
     */
    suspend fun saveBangumiImageSource(displayName: String) = withContext(Dispatchers.IO) {
        val source = BangumiImageSource.fromDisplayName(displayName)
        prefs.edit().putString(KEY_BANGUMI_IMAGE_SOURCE, source.key).apply()
    }

    suspend fun getBangumiImageSourceDisplayName(): String = withContext(Dispatchers.IO) {
        val key = prefs.getString(KEY_BANGUMI_IMAGE_SOURCE, BangumiImageSource.DIRECT.key)
            ?: BangumiImageSource.DIRECT.key
        BangumiImageSource.fromKey(key).displayName
    }


    // ==================== 其他设置 ====================

    /**
     * 保存 M3U8 代理 URL
     */
    suspend fun saveM3u8ProxyUrl(url: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_M3U8_PROXY_URL, url).apply()
    }

    /**
     * 获取 M3U8 代理 URL
     */
    suspend fun getM3u8ProxyUrl(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_M3U8_PROXY_URL, "") ?: ""
    }

    /**
     * 保存 TMDB API Key
     */
    suspend fun saveTmdbApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_TMDB_API_KEY, apiKey).apply()
    }

    /**
     * 获取 TMDB API Key
     */
    suspend fun getTmdbApiKey(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_TMDB_API_KEY, "") ?: ""
    }

    // ==================== Cookie 解析 ====================

    /**
     * 从 Cookies 中解析用户角色
     */
    fun parseRoleFromCookies(cookies: String?): String {
        if (cookies.isNullOrBlank()) {
            return "user"
        }

        return try {
            // 解析 cookies 字符串
            val cookieMap = mutableMapOf<String, String>()
            cookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                val firstEqualIndex = trimmed.indexOf('=')
                if (firstEqualIndex > 0) {
                    val key = trimmed.substring(0, firstEqualIndex)
                    val value = trimmed.substring(firstEqualIndex + 1)
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        cookieMap[key] = value
                    }
                }
            }

            val authCookie = cookieMap["auth"]
            if (authCookie == null) {
                return "user"
            }

            // 处理可能的双重编码
            var decoded = java.net.URLDecoder.decode(authCookie, "UTF-8")
            
            // 如果解码后仍然包含 %，说明是双重编码，需要再次解码
            if (decoded.contains("%")) {
                decoded = java.net.URLDecoder.decode(decoded, "UTF-8")
            }

            val authData = JSONObject(decoded)
            val role = authData.optString("role", "user")
            role
        } catch (e: Exception) {
            "user"
        }
    }

    // ==================== 同步方法（用于非协程环境） ====================

    /**
     * 同步获取服务器地址
     */
    fun getServerUrlSync(): String? = prefs.getString(KEY_SERVER_URL, null)

    /**
     * 同步获取 Cookies
     */
    fun getCookiesSync(): String? = prefs.getString(KEY_COOKIES, null)

    /**
     * 同步检查是否已登录
     */
    fun isLoggedInSync(): Boolean = prefs.getBoolean(KEY_HAS_LOGIN, false)

    /**
     * 同步获取豆瓣数据源 Key
     */
    fun getDoubanDataSourceKeySync(): String =
        prefs.getString(KEY_DOUBAN_DATA_SOURCE, DoubanDataSource.DIRECT.key)
            ?: DoubanDataSource.DIRECT.key

    /**
     * 同步获取豆瓣图片源 Key。
     * 本地模式无服务端，若用户先前选过 SERVER_PROXY，运行时兜底回落为 DIRECT。
     */
    fun getDoubanImageSourceKeySync(): String {
        val raw = prefs.getString(KEY_DOUBAN_IMAGE_SOURCE, DoubanImageSource.DIRECT.key)
            ?: DoubanImageSource.DIRECT.key
        return if (isLocalModeCache && raw == DoubanImageSource.SERVER_PROXY.key)
            DoubanImageSource.DIRECT.key
        else raw
    }

    /**
     * 同步获取 Bangumi 数据源 Key
     */
    fun getBangumiDataSourceKeySync(): String =
        prefs.getString(KEY_BANGUMI_DATA_SOURCE, BangumiDataSource.DIRECT.key)
            ?: BangumiDataSource.DIRECT.key

    /**
     * 同步获取 Bangumi 图片源 Key。本地模式兜底同 douban。
     */
    fun getBangumiImageSourceKeySync(): String {
        val raw = prefs.getString(KEY_BANGUMI_IMAGE_SOURCE, BangumiImageSource.DIRECT.key)
            ?: BangumiImageSource.DIRECT.key
        return if (isLocalModeCache && raw == BangumiImageSource.SERVER_PROXY.key)
            BangumiImageSource.DIRECT.key
        else raw
    }

    // ---- 播放器 ----
    suspend fun savePlayerType(displayName: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_PLAYER_TYPE, PlayerType.fromDisplayName(displayName).key).apply()
    }
    suspend fun getPlayerTypeDisplayName(): String = withContext(Dispatchers.IO) {
        PlayerType.fromKey(prefs.getString(KEY_PLAYER_TYPE, PlayerType.EXOPLAYER.key) ?: PlayerType.EXOPLAYER.key).displayName
    }
    fun getPlayerTypeSync(): PlayerType =
        PlayerType.fromKey(prefs.getString(KEY_PLAYER_TYPE, PlayerType.EXOPLAYER.key) ?: PlayerType.EXOPLAYER.key)

    // ---- 解码 ----
    suspend fun saveDecodeMode(displayName: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_DECODE_MODE, DecodeMode.fromDisplayName(displayName).key).apply()
    }
    suspend fun getDecodeModeDisplayName(): String = withContext(Dispatchers.IO) {
        DecodeMode.fromKey(prefs.getString(KEY_DECODE_MODE, DecodeMode.HARDWARE.key) ?: DecodeMode.HARDWARE.key).displayName
    }
    fun getDecodeModeSync(): DecodeMode =
        DecodeMode.fromKey(prefs.getString(KEY_DECODE_MODE, DecodeMode.HARDWARE.key) ?: DecodeMode.HARDWARE.key)

    // ---- 搜索发起方 ----
    suspend fun saveSearchOrigin(displayName: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_SEARCH_ORIGIN, SearchOrigin.fromDisplayName(displayName).key).apply()
    }
    suspend fun getSearchOriginDisplayName(): String = withContext(Dispatchers.IO) {
        SearchOrigin.fromKey(prefs.getString(KEY_SEARCH_ORIGIN, SearchOrigin.TV_DIRECT.key) ?: SearchOrigin.TV_DIRECT.key).displayName
    }
    fun getSearchOriginSync(): SearchOrigin {
        if (isLocalModeCache) return SearchOrigin.TV_DIRECT
        return SearchOrigin.fromKey(prefs.getString(KEY_SEARCH_ORIGIN, SearchOrigin.TV_DIRECT.key) ?: SearchOrigin.TV_DIRECT.key)
    }
}

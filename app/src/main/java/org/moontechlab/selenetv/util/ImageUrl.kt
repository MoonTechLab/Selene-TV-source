package org.moontechlab.selenetv.util

import org.moontechlab.selenetv.service.AppDataService
import java.net.URLEncoder

/** 预编译正则，避免每次调用重复创建 */
private val DOUBAN_IMAGE_REGEX = Regex("""img\d+\.doubanio\.com""")

/** Bangumi 官方图片 host；命中即视为 Bangumi 图片，按 Bangumi 图片源配置改写。 */
private val BANGUMI_IMAGE_HOSTS = listOf("lain.bgm.tv", "bgm.tv")

/**
 * 把原始 URL 包装成自托管服务的 image-proxy URL；serverUrl 未配置时返回 null，由调用方落回直连。
 */
private fun serverProxyOrNull(originalUrl: String): String? {
    val server = AppDataService.getServerUrlSync()
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val encoded = URLEncoder.encode(originalUrl, "UTF-8")
    return "$server/api/image-proxy?url=$encoded"
}

/**
 * 获取处理后的图片 URL：
 * - 豆瓣（doubanio.com）按 DoubanImageSource 改写
 * - Bangumi（lain.bgm.tv / bgm.tv）按 BangumiImageSource 改写
 * - 其它原样返回
 */
suspend fun getImageUrl(originalUrl: String): String = getImageUrlSync(originalUrl)

/**
 * 同步版本：所有数据均来自 SharedPreferences 内存缓存，可在非协程环境使用。
 */
fun getImageUrlSync(originalUrl: String): String {
    if (originalUrl.isEmpty()) return originalUrl

    return when {
        originalUrl.contains("doubanio.com") -> when (AppDataService.getDoubanImageSourceKeySync()) {
            "server_proxy" -> serverProxyOrNull(originalUrl) ?: originalUrl
            "official_cdn" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img3.doubanio.com")
            "cdn_tencent"  -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img.doubanio.cmliussss.net")
            "cdn_aliyun"   -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img.doubanio.cmliussss.com")
            else           -> originalUrl
        }
        BANGUMI_IMAGE_HOSTS.any { originalUrl.contains(it) } -> when (AppDataService.getBangumiImageSourceKeySync()) {
            "server_proxy" -> serverProxyOrNull(originalUrl) ?: originalUrl
            else           -> originalUrl
        }
        else -> originalUrl
    }
}

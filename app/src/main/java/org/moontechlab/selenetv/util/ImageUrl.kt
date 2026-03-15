package org.moontechlab.selenetv.util

import org.moontechlab.selenetv.service.AppDataService

/** 预编译正则，避免每次调用重复创建 */
private val DOUBAN_IMAGE_REGEX = Regex("""img\d+\.doubanio\.com""")

/**
 * 获取处理后的图片 URL
 * 如果是豆瓣图片（包含 doubanio.com），根据用户设置的图片源进行 CDN 替换
 */
suspend fun getImageUrl(originalUrl: String): String {
    if (originalUrl.isEmpty()) return originalUrl

    // 检查是否为豆瓣图片
    if (originalUrl.contains("doubanio.com")) {
        val imageSourceKey = AppDataService.getDoubanImageSourceKey()
        return when (imageSourceKey) {
            "official_cdn" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img3.doubanio.com")
            "cdn_tencent" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img.doubanio.cmliussss.net")
            "cdn_aliyun" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img.doubanio.cmliussss.com")
            else -> originalUrl // "direct" 或其他情况
        }
    }

    return originalUrl
}

/**
 * 同步版本的 getImageUrl，用于不支持协程的场景
 */
fun getImageUrlSync(originalUrl: String): String {
    if (originalUrl.isEmpty()) return originalUrl

    // 检查是否为豆瓣图片
    if (originalUrl.contains("doubanio.com")) {
        val imageSourceKey = AppDataService.getDoubanImageSourceKeySync()
        return when (imageSourceKey) {
            "official_cdn" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img3.doubanio.com")
            "cdn_tencent" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img.doubanio.cmliussss.net")
            "cdn_aliyun" -> originalUrl.replace(DOUBAN_IMAGE_REGEX, "img.doubanio.cmliussss.com")
            else -> originalUrl // "direct" 或其他情况
        }
    }

    return originalUrl
}

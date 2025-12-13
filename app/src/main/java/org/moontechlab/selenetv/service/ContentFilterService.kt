package org.moontechlab.selenetv.service

/**
 * 内容过滤服务
 * 用于过滤不适当的内容
 */
object ContentFilterService {
    
    // 需要过滤的关键词列表
    private val filterKeywords = listOf(
        "伦理片", "福利", "里番动漫", "门事件", "萝莉少女",
        "制服诱惑", "国产传媒", "cosplay", "黑丝诱惑", "无码",
        "日本无码", "有码", "日本有码", "SWAG", "网红主播",
        "色情片", "同性片", "福利视频", "福利片", "写真热舞",
        "倫理片", "理论片", "韩国伦理", "港台三级", "电影解说",
        "伦理", "日本伦理"
    )
    
    /**
     * 检查是否应该过滤该内容
     * @param typeName 类型名称
     * @return true 表示应该过滤，false 表示不过滤
     */
    fun shouldFilter(typeName: String?): Boolean {
        if (typeName.isNullOrBlank()) return false
        return filterKeywords.any { keyword ->
            typeName.contains(keyword, ignoreCase = true)
        }
    }
}

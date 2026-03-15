package org.moontechlab.selenetv.model

/**
 * 直播源数据模型
 */
data class LiveSource(
    val key: String,
    val name: String,
    val url: String,
    val ua: String = "",
    val epg: String = "",
    val from: String = "",
    val disabled: Boolean = false
)

package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 直播源数据模型
 */
@Serializable
data class LiveSource(
    val key: String,
    val name: String,
    val url: String,
    val ua: String = "",
    val epg: String = "",
    val from: String = "",
    val disabled: Boolean = false
)

package org.moontechlab.selenetv.model

/**
 * 直播频道数据模型
 */
data class LiveChannel(
    val id: String,
    val tvgId: String = "",
    val name: String,
    val logo: String = "",
    val group: String = "",
    val url: String
)

/**
 * 直播频道分组
 */
data class LiveChannelGroup(
    val name: String,
    val channels: List<LiveChannel>
)

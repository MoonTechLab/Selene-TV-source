package org.moontechlab.selenetv.screen.player

import org.moontechlab.selenetv.model.LiveChannel
import org.moontechlab.selenetv.model.LiveChannelGroup
import org.moontechlab.selenetv.model.LiveSource

/**
 * 直播播放页入口参数（内存覆盖层，不发网络请求）。
 * 由直播页频道点击组装：携带起播频道、所属源（提供 UA）、已分组频道列表（频道面板用）。
 *
 * 直播无时长/进度/续看，故不复用 PlayerLaunch；仅复用 PlayerController + MediaHttp 播放引擎。
 */
data class LiveLaunch(
    /** 起播频道。 */
    val channel: LiveChannel,
    /** 频道所属直播源（提供 UA 与归属）。 */
    val source: LiveSource,
    /** 当前源的已分组频道列表（频道面板选台用，避免重新拉取）。 */
    val groups: List<LiveChannelGroup>,
)

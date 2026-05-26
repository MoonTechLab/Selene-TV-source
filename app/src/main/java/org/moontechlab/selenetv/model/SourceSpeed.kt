package org.moontechlab.selenetv.model

/**
 * 单个播放源的测速状态。
 * - TESTING：测速进行中（卡片显示占位、整体变暗）
 * - DONE：测速成功，[SourceSpeed] 携带分辨率与下载速度
 * - FAILED：拉流失败 / 超时
 */
enum class SpeedTestState { TESTING, DONE, FAILED }

/**
 * 一个播放源的测速结果。仅内存持有，不参与序列化。
 *
 * 评分由 [org.moontechlab.selenetv.service.SpeedTestService] 在拿到全部结果后基于
 * 分辨率 + 下载速度计算（见该类）；本结构只保存原始指标与展示用文案。
 * 不含延迟：对视频流（缓冲后顺序播放）而言 RTT 几乎不影响观感，故测速不测延迟。
 */
data class SourceSpeed(
    val state: SpeedTestState,
    /** 分辨率宽度（px），用于打分；未知为 0。 */
    val widthPx: Int = 0,
    /** 下载速度（KB/s），用于打分；未知/失败为 0。 */
    val speedKbps: Double = 0.0,
    /** 展示用分辨率档位，如 "1080p"；未知为空串。 */
    val quality: String = "",
    /** 展示用速度，如 "2.1MB/s"；失败为空串。 */
    val speedLabel: String = "",
)

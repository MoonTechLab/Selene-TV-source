package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/** 跳过段类型。OUTRO 含片尾字幕 / 下集预告。 */
@Serializable
enum class SkipType { INTRO, RECAP, OUTRO }

/**
 * 一个可跳过区间。endMs == Long.MAX_VALUE 表示「到片尾」（消费时夹到 durationMs）。
 */
@Serializable
data class SkipSegment(
    val type: SkipType,
    val startMs: Long,
    val endMs: Long,
)

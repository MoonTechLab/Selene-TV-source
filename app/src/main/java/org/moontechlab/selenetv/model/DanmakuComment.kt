package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 统一弹幕模型——所有弹幕源（B站/腾讯/爱奇艺…）解析后都归一到此结构，
 * 与 misaka_danmu_server 的 get_comments 契约对齐，方便后续脚本引擎桥接。
 *
 * @param timeMs  弹幕出现时间（毫秒，相对视频起点）
 * @param mode    弹幕模式：1=滚动 4=底部 5=顶部（对齐 B站 <d> 与弹弹play 规范）
 * @param color   颜色，十进制 RGB（如 16777215 = 0xFFFFFF 白）
 * @param content 弹幕文本
 */
@Serializable
data class DanmakuComment(
    val timeMs: Long,
    val mode: Int,
    val color: Int,
    val content: String,
) {
    companion object {
        const val MODE_SCROLL = 1
        const val MODE_BOTTOM = 4
        const val MODE_TOP = 5
    }
}

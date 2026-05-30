package org.moontechlab.selenetv.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 播放器后端统一抽象。两屏（PlayerScreen / LivePlayerScreen）只依赖本接口。
 * 状态字段须为 Compose 可观测（实现用 mutableStateOf 支撑）。位置/时长单位毫秒。
 */
interface VideoPlayer {
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isBuffering: Boolean
    /** 本次媒体是否成功起播过；每次 load 重置为 false。用于落播放记录判定。 */
    val hasStarted: Boolean
    /** 抽象错误：null=无错；非 null=有错（字符串仅用于展示/日志，调用方只做 null 判定）。 */
    val error: String?
    /** 播放自然结束回调，宿主用于自动下一集。 */
    var onEnded: () -> Unit
    /** 播放倍速（1f=原速）。直播实现可忽略。 */
    var speed: Float
    /** 画面缩放模式。直播实现可忽略，默认 FIT。 */
    var resizeMode: VideoResizeMode

    fun load(url: String, startMs: Long, playWhenReady: Boolean = true)
    fun seekTo(ms: Long)
    fun togglePlay()
    /** 强制暂停（不是切换）。宿主在 App 切后台时调用。 */
    fun pause()
    /** 跳到直播边缘；点播实现等价为 seek 到默认位置。直播 togglePlay 恢复前调用。 */
    fun seekToLiveEdge()
    /** 把后端当前位置/缓冲/时长同步到状态；由宿主周期调用。 */
    fun syncProgress()
    fun release()

    /** 视频画面：各实现自行渲染（Exo→PlayerView；MPV→SurfaceView）。 */
    @Composable
    fun VideoSurface(modifier: Modifier)
}

enum class VideoResizeMode {
    FIT,   // 适应：保持比例，留黑边（默认）
    ZOOM,  // 铺满：保持比例，裁剪填满
    FILL,  // 拉伸：变形填满
}

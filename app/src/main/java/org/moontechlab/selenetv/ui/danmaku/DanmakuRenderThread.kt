package org.moontechlab.selenetv.ui.danmaku

import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import kotlin.math.abs

/**
 * 专用渲染线程：自带 Looper + Choreographer 的 vsync 循环，从 Surface.lockHardwareCanvas()
 * 拿 GPU 加速 Canvas 绘制。所有引擎状态变更经本线程的 Handler 串行执行 → 无锁。
 *
 * 空闲门控：仅在动画态（播放 或 预览）持续投递帧；静止态绘制一帧冻结画面后停循环，
 * 由 setPlayback/requestRedraw 重新 kick。
 */
class DanmakuRenderThread(
    private val surface: Surface,
    private val engine: DanmakuEngine,
) : HandlerThread("DanmakuRender") {

    @Volatile private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())  // 细描影提升暗背景可读性
    }

    // 以下仅本线程触碰
    private var hostPositionMs = 0L
    private var isPlaying = false
    private var previewMode = false
    private var playSpeed = 1f
    private var engineClockMs = 0L
    private var lastFrameNs = 0L
    private var lastSyncNs = 0L
    private var looping = false

    private val frameCallback = Choreographer.FrameCallback { onVsync(it) }

    override fun onLooperPrepared() {
        val h = Handler(looper)
        handler = h
        h.post {
            choreographer = Choreographer.getInstance()
            kick()
        }
    }

    fun post(r: () -> Unit) { handler?.post(r) }

    fun setPlayback(positionMs: Long, playing: Boolean, speed: Float, preview: Boolean) = post {
        if (!preview && abs(positionMs - engineClockMs) > 1200) {  // 用户拖拽即时纠正
            engineClockMs = positionMs
            engine.seekTo(positionMs)
        }
        hostPositionMs = positionMs
        isPlaying = playing
        previewMode = preview
        playSpeed = speed
        kick()
    }

    /** 起线程前一次性播种初始播放态与时钟（仅在 start() 之前调用，此时无渲染线程，直接赋值安全）。 */
    fun setInitialPlayback(positionMs: Long, playing: Boolean, speed: Float, preview: Boolean) {
        hostPositionMs = positionMs
        isPlaying = playing
        playSpeed = speed
        previewMode = preview
        engineClockMs = positionMs
    }

    fun requestRedraw() = post { kick() }

    private fun kick() {
        if (looping) return
        looping = true
        lastFrameNs = 0L
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun onVsync(frameNs: Long) {
        val animating = isPlaying || previewMode
        if (lastFrameNs != 0L && animating) {
            val dtMs = (frameNs - lastFrameNs) / 1_000_000f
            engineClockMs += (dtMs * playSpeed).toLong()
        }
        lastFrameNs = frameNs

        if (animating && isPlaying && !previewMode) {           // 周期漂移校正
            if (lastSyncNs == 0L) lastSyncNs = frameNs
            else if (frameNs - lastSyncNs > 2_000_000_000L) {
                lastSyncNs = frameNs
                if (abs(engineClockMs - hostPositionMs) > 1200) {
                    engineClockMs = hostPositionMs
                    engine.seekTo(engineClockMs)
                }
            }
        }

        engine.advance(engineClockMs)
        drawFrame()

        if (animating) choreographer?.postFrameCallback(frameCallback)
        else looping = false                                    // 静止：冻结画面已绘，停循环省 CPU
    }

    private fun drawFrame() {
        if (!surface.isValid) return
        val canvas = try { surface.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
        try {
            DanmakuRenderer.draw(canvas, paint, engine)
        } finally {
            try { surface.unlockCanvasAndPost(canvas) } catch (e: Exception) { /* surface 已失效 */ }
        }
    }

    fun quitAndJoin() {
        post {
            choreographer?.removeFrameCallback(frameCallback)
            looping = false
        }
        quitSafely()
        try { join(500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

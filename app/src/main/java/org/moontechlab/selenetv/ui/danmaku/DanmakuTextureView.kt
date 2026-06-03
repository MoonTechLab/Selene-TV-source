package org.moontechlab.selenetv.ui.danmaku

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/**
 * 承载弹幕的 TextureView：onAvailable 建 Surface 起渲染线程，onDestroyed 干净收尾（无反射、无泄漏）。
 * submit* 把变更交给渲染线程；onAvailable 之前的最新状态缓存下来，避免早到的提交丢失。
 */
class DanmakuTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val engine = DanmakuEngine(PaintTextMeasurer())
    private var renderThread: DanmakuRenderThread? = null
    private var surface: Surface? = null

    private var pendingItems: List<DanmakuItem> = emptyList()
    private var pendingConfig: DanmakuEngineConfig? = null
    private var lastPlayback: PlaybackState? = null

    private data class PlaybackState(val pos: Long, val playing: Boolean, val speed: Float, val preview: Boolean)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun submitItems(list: List<DanmakuItem>) {
        pendingItems = list
        renderThread?.let { t -> t.post { engine.setItems(list) }; t.requestRedraw() }
    }

    fun submitConfig(config: DanmakuEngineConfig) {
        pendingConfig = config
        renderThread?.let { t -> t.post { engine.setConfig(config) }; t.requestRedraw() }
    }

    fun submitPlayback(pos: Long, playing: Boolean, speed: Float, preview: Boolean) {
        lastPlayback = PlaybackState(pos, playing, speed, preview)
        renderThread?.setPlayback(pos, playing, speed, preview)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val s = Surface(st)
        surface = s
        engine.setViewport(w, h)
        pendingConfig?.let { engine.setConfig(it) }
        engine.setItems(pendingItems)
        val t = DanmakuRenderThread(s, engine)
        renderThread = t
        t.start()
        lastPlayback?.let { t.setPlayback(it.pos, it.playing, it.speed, it.preview) }
        t.requestRedraw()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        renderThread?.let { t -> t.post { engine.setViewport(w, h) }; t.requestRedraw() }
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderThread?.quitAndJoin()
        renderThread = null
        surface?.release()
        surface = null
        return true
    }
}

package org.moontechlab.selenetv.screen.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 基于 libmpv 的 VideoPlayer 实现。仅在 API>=26 由 PlayerFactory 构造。
 * 状态通过 MPVLib 属性观察回调（工作线程）切回主线程更新。
 *
 * 注：libmpv 1.0.0（dev.jdtech.mpv）为实例式 API——`MPVLib.create(ctx)` 返回实例，
 * 其余 init/destroy/attachSurface/command/setOptionString/observeProperty/addObserver 等均为实例方法；
 * 格式/事件常量在嵌套对象 `MPVLib.MpvFormat` / `MPVLib.MpvEvent` 中。
 */
class MpvPlayerController(
    context: Context,
    userAgent: String?,
    decodeMode: AppDataService.DecodeMode,
) : VideoPlayer, MPVLib.EventObserver {

    private val mpv: MPVLib = MPVLib.create(context)!!

    private val main = Handler(Looper.getMainLooper())
    private var pendingUrl: String? = null
    private var pendingStartMs: Long = 0
    private var pendingPlayWhenReady: Boolean = true
    private var surfaceAttached = false
    @Volatile private var released = false

    // 切频道/切集用 loadfile ... replace 会让「旧文件」先收到一个 END_FILE(stop)。
    // EventObserver 只给 event(id)、拿不到结束原因，无法区分「旧文件被替换」与「新文件加载失败」。
    // 故记录：当前是否有文件在播(fileActive)，以及 replace 时应忽略的旧文件停止事件数(pendingStopEndFiles)。
    // 这些变量只在主线程读写（doLoad 与 event 的 main.post 回调都在主线程）。
    private var fileActive = false
    private var pendingStopEndFiles = 0

    override var positionMs by mutableLongStateOf(0L); private set
    override var durationMs by mutableLongStateOf(0L); private set
    override var bufferedMs by mutableLongStateOf(0L); private set
    override var isPlaying by mutableStateOf(false); private set
    override var isBuffering by mutableStateOf(false); private set
    override var error by mutableStateOf<String?>(null); private set
    override var hasStarted by mutableStateOf(false); private set
    override var onEnded: () -> Unit = {}
    override var speed: Float = 1f
        set(value) {
            field = value
            if (!released) mpv.setPropertyString("speed", value.toString())
        }

    override var resizeMode: VideoResizeMode = VideoResizeMode.FIT
        set(value) {
            field = value
            if (released) return
            when (value) {
                VideoResizeMode.FIT -> {
                    mpv.setPropertyString("keepaspect", "yes")
                    mpv.setPropertyString("panscan", "0")
                }
                VideoResizeMode.ZOOM -> {
                    mpv.setPropertyString("keepaspect", "yes")
                    mpv.setPropertyString("panscan", "1.0")
                }
                VideoResizeMode.FILL -> {
                    mpv.setPropertyString("keepaspect", "no")
                    mpv.setPropertyString("panscan", "0")
                }
            }
        }

    init {
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("tls-verify", "no")
        mpv.setOptionString("ytdl", "no")
        when (decodeMode) {
            AppDataService.DecodeMode.HARDWARE -> {
                // 优先直通（零拷贝）MediaCodec，失败再退回拷，最后软解（mpv 0.41 行为：按列表
                // 逐个尝试初始化，全失败才软解）。直通解码输出经 AImageReader 直接作为 GL 纹理被
                // vo=gpu 采样，不回读系统内存——这也是项目 MPV_MIN_SDK=26 的由来（AImageReader 需 API 26）。
                // 旧值仅 "mediacodec-copy" 会把每帧从 GPU 回拷到 CPU 再重新上传，4K 像素吞吐约为
                // 1080p 的 4 倍，直接打满内存带宽 → 解码追不上 → 音视频同时卡顿（issue #14）；
                // 1080p 拷贝路径尚能扛、4K 则不行，与「1080p 流畅、4K 卡」吻合。列表第二项 -copy
                // 保留为兜底：直通初始化失败的设备自动退回原行为，不丢兼容性。
                mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
                mpv.setOptionString("hwdec-codecs", "h264,hevc,vp9,av1")
            }
            AppDataService.DecodeMode.SOFTWARE -> mpv.setOptionString("hwdec", "no")
        }
        userAgent?.let { mpv.setOptionString("user-agent", MediaHttp.resolveLiveUa(it)) }
        mpv.init()
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.addObserver(this)
    }

    override fun load(url: String, startMs: Long, playWhenReady: Boolean) {
        error = null
        hasStarted = false
        positionMs = 0; durationMs = 0; bufferedMs = 0
        // mpv 的 paused-for-cache 只反映「播放中」的缓存停顿，不覆盖初始连接/解封装/首帧解码阶段。
        // 这里在开始载入时主动置 true（等价于 ExoPlayer prepare() 触发的 STATE_BUFFERING），
        // 让加载月牙立即出现，避免首帧前一段纯黑无反馈；首帧到达（time-pos>0）时清掉。
        isBuffering = true
        if (!surfaceAttached) {
            pendingUrl = url; pendingStartMs = startMs
            pendingPlayWhenReady = playWhenReady
            return
        }
        doLoad(url, startMs, playWhenReady)
    }

    private fun doLoad(url: String, startMs: Long, playWhenReady: Boolean) {
        // 若已有文件在播，replace 会先给旧文件发一个 END_FILE(stop)，需忽略，避免误判为新源加载失败。
        if (fileActive) pendingStopEndFiles++
        fileActive = true
        val startSec = (startMs / 1000.0).coerceAtLeast(0.0)
        // loadfile 语法：loadfile <url> [<flags> [<index> [<options>]]]。
        // start 是 option，必须放在第 4 个位置（index 之后）；放在 index 位会被当成整数解析失败、
        // 导致整条 loadfile 被拒、什么都不播（黑屏）。start=0 时不传 option。
        if (startSec > 0) {
            mpv.command(arrayOf("loadfile", url, "replace", "0", "start=$startSec"))
        } else {
            mpv.command(arrayOf("loadfile", url, "replace"))
        }
        mpv.setPropertyString("pause", if (playWhenReady) "no" else "yes")
    }

    override fun seekTo(ms: Long) {
        mpv.command(arrayOf("seek", (ms / 1000.0).coerceAtLeast(0.0).toString(), "absolute"))
    }

    override fun togglePlay() {
        mpv.setPropertyString("pause", if (isPlaying) "yes" else "no")
    }

    override fun pause() { mpv.setPropertyString("pause", "yes") }

    // mpv 直播流通常不可 seek，解暂停后自然回到边缘；此处 no-op 即可。
    override fun seekToLiveEdge() {}

    override fun syncProgress() { /* MPV 走属性回调推送，无需主动拉取 */ }

    override fun release() {
        if (released) return
        released = true
        mpv.removeObserver(this)
        // mpv.destroy()（尤其硬解 MediaCodec teardown）在主线程会阻塞 ~150ms，正好落在返回直播 tab
        // 的焦点还原窗口内（还原协程也跑在主线程），把它挤占/饿死 → 焦点还原偶发失败或抖动。
        // 放到后台线程释放：此时已 removeObserver、surface 也已在 surfaceDestroyed 里 detach，
        // 不会再有其它 MPVLib 调用与之并发。
        val player = mpv
        Thread({
            try { player.command(arrayOf("stop")); player.destroy() } catch (_: Throwable) {}
        }, "mpv-release").start()
    }

    // ---- MPVLib.EventObserver（工作线程回调，切主线程更新 Compose 状态）----
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {
        main.post {
            if (released) return@post
            when (property) {
                "pause" -> isPlaying = !value
                "paused-for-cache" -> isBuffering = value
                "eof-reached" -> if (value) onEnded()
            }
        }
    }
    override fun eventProperty(property: String, value: Double) {
        main.post {
            if (released) return@post
            when (property) {
                "time-pos" -> {
                    positionMs = (value * 1000).toLong().coerceAtLeast(0)
                    // 首帧到达：起播完成，关掉初始加载月牙（之后的重缓冲交回 paused-for-cache）。
                    if (!hasStarted && value > 0) { hasStarted = true; isBuffering = false }
                }
                "duration" -> durationMs = (value * 1000).toLong().coerceAtLeast(0)
                "demuxer-cache-time" -> bufferedMs = (value * 1000).toLong().coerceAtLeast(0)
            }
        }
    }
    override fun eventProperty(property: String, value: String) {}
    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) main.post {
            if (released) return@post
            // 旧文件因 replace 停止：吞掉，不算失败（切频道/切集的正常现象）。
            if (pendingStopEndFiles > 0) { pendingStopEndFiles--; return@post }
            fileActive = false
            // 走到这里是「新文件」自己结束：未起播过即视为加载失败（死链/无法解码）。
            if (!hasStarted) error = "mpv: playback failed"
        }
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (released) return
                            mpv.attachSurface(holder.surface)
                            mpv.setOptionString("force-window", "yes")
                            mpv.setPropertyString("vo", "gpu")
                            surfaceAttached = true
                            pendingUrl?.let { doLoad(it, pendingStartMs, pendingPlayWhenReady); pendingUrl = null }
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                            if (released) return
                            mpv.setPropertyString("android-surface-size", "${w}x$ht")
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (released) return
                            surfaceAttached = false
                            mpv.setPropertyString("vo", "null")
                            mpv.setOptionString("force-window", "no")
                            mpv.detachSurface()
                        }
                    })
                }
            },
            modifier = modifier,
        )
    }
}

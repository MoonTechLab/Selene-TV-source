package org.moontechlab.selenetv.screen.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 封装 ExoPlayer，把播放状态暴露为 Compose State 供 PlayerScreen 驱动现有控制层。
 * 位置/时长用毫秒，与外壳进度条约定一致。位置由调用方周期 [syncProgress] 拉取。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerController(context: Context) {

    /** 仅用于绑定 PlayerView（playerView.player = exo）；其余操作请走本类方法。 */
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(MediaHttp.dataSourceFactory))
        .build()

    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var bufferedMs by mutableLongStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var error by mutableStateOf<PlaybackException?>(null); private set

    /** 播放自然结束（STATE_ENDED）回调，宿主用于自动下一集。 */
    var onEnded: () -> Unit = {}

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { val d = exo.duration; if (d > 0) durationMs = d }
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(e: PlaybackException) {
            error = e
        }
    }

    init {
        exo.addListener(listener)
    }

    /** 载入并播放一个 URL；startMs>0 时从该处续播。error 清零。 */
    @MainThread
    fun load(url: String, startMs: Long, playWhenReady: Boolean = true) {
        error = null
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = playWhenReady
        syncProgress()
    }

    @MainThread
    fun seekTo(ms: Long) {
        exo.seekTo(ms.coerceAtLeast(0))
        syncProgress()
    }

    @MainThread
    fun togglePlay() {
        exo.playWhenReady = !exo.playWhenReady
    }

    /** 把 ExoPlayer 的当前位置/缓冲/时长同步到 State；由宿主轮询调用。 */
    @MainThread
    fun syncProgress() {
        positionMs = exo.currentPosition.coerceAtLeast(0)
        bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        val d = exo.duration
        if (d > 0) durationMs = d
    }

    @MainThread
    fun release() {
        exo.removeListener(listener)
        exo.release()
    }
}

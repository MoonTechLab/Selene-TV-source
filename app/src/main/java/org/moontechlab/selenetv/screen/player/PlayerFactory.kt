package org.moontechlab.selenetv.screen.player

import android.content.Context
import android.os.Build
import androidx.media3.datasource.DataSource
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.AppDataService.PlayerType
import org.moontechlab.selenetv.service.MediaHttp

/** libmpv 要求 API 26+；低于 26 时把 MPV 降级为 ExoPlayer（.so 永不加载）。 */
const val MPV_MIN_SDK = 26

fun resolveEffectivePlayerType(requested: PlayerType, sdkInt: Int): PlayerType =
    if (requested == PlayerType.MPV && sdkInt < MPV_MIN_SDK) PlayerType.EXOPLAYER else requested

/**
 * 按设置 + 系统版本创建播放器后端。
 * @param userAgent 直播源 UA；点播传 null（用默认 DataSource）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerFactory {
    fun create(context: Context, userAgent: String? = null): VideoPlayer {
        val decode = AppDataService.getDecodeModeSync()
        val type = resolveEffectivePlayerType(AppDataService.getPlayerTypeSync(), Build.VERSION.SDK_INT)
        fun exo(): VideoPlayer {
            val ds: DataSource.Factory =
                if (userAgent != null) MediaHttp.dataSourceFactory(userAgent) else MediaHttp.dataSourceFactory
            return PlayerController(context, ds, decode)
        }
        return when (type) {
            PlayerType.MPV -> try {
                MpvPlayerController(context, userAgent, decode)
            } catch (e: Throwable) {
                android.util.Log.e("PlayerFactory", "MPV 初始化失败，回退 ExoPlayer", e)
                exo()
            }
            PlayerType.EXOPLAYER -> exo()
        }
    }
}

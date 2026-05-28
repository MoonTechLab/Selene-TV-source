package org.moontechlab.selenetv.screen.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.data.Repositories
import org.moontechlab.selenetv.model.SearchResult

/**
 * 播放记录上报：内置节流（见 [PlayProgress.shouldSave]）。
 * 用独立 IO scope 发起 fire-and-forget 保存，使退出/切换时的保存不被 composition 取消打断。
 */
class PlayProgressReporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSavedPositionMs = -1L
    private var lastSaveAtMs = 0L

    fun save(
        source: SearchResult,
        title: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (!PlayProgress.shouldSave(force, positionMs, lastSavedPositionMs, now - lastSaveAtMs)) return
        lastSavedPositionMs = positionMs
        lastSaveAtMs = now
        val record = PlayProgress.buildRecord(source, title, episodeIndex, positionMs, durationMs, now)
        scope.launch {
            try {
                Repositories.userData().savePlayRecord(record)
            } catch (_: Exception) {
                // 进度保存失败不打断播放
            }
        }
    }

    /** 切集 / 切源后调用，清空节流，使下一次保存不被 10s 窗口拦住。 */
    fun resetThrottle() {
        lastSavedPositionMs = -1L
        lastSaveAtMs = 0L
    }
}

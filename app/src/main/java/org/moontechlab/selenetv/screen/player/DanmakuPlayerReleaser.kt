package org.moontechlab.selenetv.screen.player

import android.os.HandlerThread
import android.util.Log
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import java.util.concurrent.Semaphore

/**
 * akdanmaku 1.0.3 的 [DanmakuPlayer.release] 在**主线程**调 `Choreographer.getInstance().removeFrameCallback(...)`，
 * 而帧回调实际 post 在 **ActionThread 自己的 Choreographer** 上——摘错线程，ActionThread 常卡在
 * `updateFrame` 的 `drawSemaphore.acquire()`（view 不再回调 releaseSemaphore），`quitSafely()` 杀不掉它 →
 * 每次 release 泄漏一个 HandlerThread + ~7MB（issue #27）。
 *
 * 反射兜底优雅回收（不 interrupt，避免抛 InterruptedException 触发刺眼的 FATAL 日志）：
 *  1. 给 ActionThread 装吞异常 handler（仅兜「act 与 engine.release 撞车」的极小概率竞态，线程正销毁，吞掉安全）；
 *  2. 置 `started=false` —— 解锁后 updateFrame 会跳过 `engine.act/draw`，不在已释放引擎上动作；
 *  3. [DanmakuPlayer.release]（quitSafely 置位 + 引擎拆除）；
 *  4. `drawSemaphore.release()` 放许可 —— 卡住的 `acquire()` 正常返回，updateFrame 收尾后 looper 即处理 quit 退出。
 * 全程 runCatching——反射失败/库改名只退化为普通 release。
 */
object DanmakuPlayerReleaser {

    fun release(player: DanmakuPlayer) {
        val cls = player.javaClass
        val actionThread: HandlerThread? = runCatching {
            cls.getDeclaredMethod("getActionThread").apply { isAccessible = true }.invoke(player) as HandlerThread
        }.getOrNull()

        runCatching {
            actionThread?.setUncaughtExceptionHandler { _, e ->
                Log.i("DanmakuRelease", "ActionThread 回收时吞异常: ${e.javaClass.simpleName}")
            }
        }
        // 让解锁后的 updateFrame 跳过 act/draw（避免在已释放引擎上动作）。
        runCatching {
            cls.getDeclaredField("started").apply { isAccessible = true }.setBoolean(player, false)
        }

        runCatching { player.release() }

        // 放许可解锁卡在 acquire() 的 ActionThread —— 它收尾后 looper 处理 quitSafely 真正退出。
        runCatching {
            val sem = cls.getDeclaredField("drawSemaphore").apply { isAccessible = true }.get(player) as Semaphore
            sem.release(2)
        }
    }
}

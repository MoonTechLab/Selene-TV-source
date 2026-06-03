package org.moontechlab.selenetv.ui.danmaku

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff

/**
 * 把引擎活跃集画到一块硬件 Canvas 上。每条 drawText 自带 alpha（颜色折入不透明度）——
 * 绝不调 saveLayer*。复用同一个 Paint，零每帧分配。
 */
object DanmakuRenderer {
    fun draw(canvas: Canvas, paint: Paint, engine: DanmakuEngine) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)  // 整屏透明清空
        val cfg = engine.config
        if (!cfg.visible) return
        paint.textSize = cfg.textSizePx
        val alpha = (cfg.opacity.coerceIn(0f, 1f) * 255f).toInt()
        for (d in engine.active) {
            paint.color = (alpha shl 24) or (d.colorRgb and 0xFFFFFF)
            canvas.drawText(d.text, d.x, d.y, paint)
        }
    }
}

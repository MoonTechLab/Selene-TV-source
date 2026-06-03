package org.moontechlab.selenetv.screen.player

import android.graphics.Canvas
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.render.SimpleRenderer
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer

/**
 * 修复 akdanmaku 1.0.3 在低端机（armv7a 真机）上「弹幕透明度不生效」。
 *
 * 根因（见 RenderSystem 字节码）：全局透明度 config.alpha 只在**缓存命中**路径生效——把缓存位图
 * blit 到画布时用 `drawPaint.setAlpha(config.alpha * itemAlpha * 255)` 整体调透。但某条弹幕的位图
 * 缓存尚未生成时（低端机缓存线程跟不上、内存吃紧长期命中不了），RenderSystem 走**直绘兜底**：
 * 直接用 SimpleRenderer 的画笔把文字画到画布上，而该画笔颜色被强制成 `textColor or 0xFF000000`
 * （完全不透明），且**不经过 drawPaint**——于是透明度被整段忽略。设备越慢兜底占比越高，最终表现
 * 为「透明度完全不生效」。纯 DEX 跨 ABI 完全一致，唯一变量是渲染时延，恰好与架构相关。
 *
 * 修法：兜底直绘画到的是 **View 的硬件画布**（全屏尺寸 / 硬件加速），缓存位图构建画到的是**一条
 * 弹幕大小的离屏 Bitmap 画布**（软件、宽度恰为该弹幕宽）。据此只在兜底路径用 saveLayerAlpha 整体
 * 调透，缓存构建与合成路径**原样不动**——既补齐兜底透明度，又不让缓存路径二次叠加（高端机既有
 * 表现零回归）。
 */
class AlphaDanmakuRenderer : SimpleRenderer() {

    override fun draw(
        item: DanmakuItem,
        canvas: Canvas,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig,
    ) {
        val alpha = config.alpha
        // 全不透明：无需调透，直接走原逻辑。
        if (alpha >= 1f) {
            super.draw(item, canvas, displayer, config)
            return
        }

        val size = measure(item, displayer, config)
        // 区分两种画布：缓存构建画到的离屏位图是软件画布且宽度恰为该弹幕宽；兜底直绘画到的是 View
        // 画布（硬件加速，或硬件加速被关时也是全屏远大于单条弹幕宽）。仅兜底路径需要补透明度。
        val onScreen = canvas.isHardwareAccelerated || canvas.width > size.width + 8
        if (!onScreen) {
            super.draw(item, canvas, displayer, config)
            return
        }

        // 兜底：缺一次整体透明度，用 saveLayerAlpha 把这条弹幕（描边+文字）整体合成调透，与缓存路径
        // 在合成期调透的效果一致，避免低端机上透明度完全不生效。
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val saved = canvas.saveLayerAlpha(0f, 0f, size.width.toFloat(), size.height.toFloat(), a)
        try {
            super.draw(item, canvas, displayer, config)
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}

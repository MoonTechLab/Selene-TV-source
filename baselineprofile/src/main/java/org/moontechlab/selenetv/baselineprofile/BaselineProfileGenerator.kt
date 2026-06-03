package org.moontechlab.selenetv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 app 级 Baseline Profile：录制启动 + 首页滚动 + 切 Tab + 卡片移焦 这几条**用户实际会卡顿**的
 * 热路径所用到的类/方法清单。生成物随 release 打包（app/src/release/generated/baselineProfiles/），
 * ART 在低端 armv7a 真机安装时据此 AOT 编译 → 重组/动画热路径不再走 JIT/解释，减少掉帧。
 *
 * 在已连接的 arm64 模拟器上运行：`./gradlew :app:generateReleaseBaselineProfile`
 * （profile 是设备无关的类/方法清单，arm64 上生成、armv7a 上同样适用）。
 *
 * TV 全程 D-pad 操作（无触摸），故旅程用 UiDevice 的方向键模拟遥控。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "org.moontechlab.selenetv",
        includeInStartupProfile = true,
    ) {
        // 启动（走 Leanback 启动 Intent）
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        // 等首页内容加载（默认已登录有数据）
        Thread.sleep(5000)

        // 首页上下滚动：触发各内容行/卡片的组合与回收
        repeat(6) {
            device.pressDPadDown()
            device.waitForIdle()
            Thread.sleep(250)
        }
        repeat(6) {
            device.pressDPadUp()
            device.waitForIdle()
            Thread.sleep(150)
        }

        // 回到顶部标签栏并左右切 Tab：触发 AnimatedTabContent 页面过渡 + 各 Tab 内容组合
        repeat(3) { device.pressDPadUp() }
        device.waitForIdle()
        repeat(4) {
            device.pressDPadRight()
            device.waitForIdle()
            Thread.sleep(900)
        }
        repeat(4) {
            device.pressDPadLeft()
            device.waitForIdle()
            Thread.sleep(900)
        }

        // 进入内容区左右移焦：触发 VideoCard 聚焦缩放动画 + LazyRow 滚动居中 + 新卡片组合
        device.pressDPadDown()
        device.waitForIdle()
        repeat(10) {
            device.pressDPadRight()
            device.waitForIdle()
            Thread.sleep(200)
        }
    }
}

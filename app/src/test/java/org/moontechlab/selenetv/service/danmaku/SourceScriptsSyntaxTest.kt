package org.moontechlab.selenetv.service.danmaku

import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Function
import org.mozilla.javascript.Context as RhinoContext
import java.io.File

/**
 * 校验 assets/danmaku 下每个弹幕源脚本在 Rhino 解释模式下能正常 eval，且定义了契约三函数。
 * 纯语法/ES5 兼容性检查（不联网、不调用）。Rhino 是纯 JVM，可在单测运行。
 */
class SourceScriptsSyntaxTest {

    private val scriptsDir = File("src/main/assets/danmaku")

    @Test
    fun allSourceScriptsParseAndDefineContract() {
        val files = scriptsDir.listFiles { f -> f.name.endsWith(".js") }
        assertTrue("没找到弹幕脚本目录: ${scriptsDir.absolutePath}", files != null && files.isNotEmpty())
        for (f in files!!) {
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = RhinoContext.VERSION_ES6
                val scope = cx.initStandardObjects()
                // 提供一个空 host，避免顶层引用报错（脚本顶层不应调用 host）
                cx.evaluateString(scope, "var host = { log: function(){}, };", "stub", 1, null)
                cx.evaluateString(scope, f.readText(), f.name, 1, null)
                for (fn in listOf("search", "getEpisodes", "getComments")) {
                    assertTrue("${f.name} 缺少函数 $fn", scope.get(fn, scope) is Function)
                }
            } catch (e: Exception) {
                throw AssertionError("脚本 ${f.name} 解析失败: ${e.message}", e)
            } finally {
                RhinoContext.exit()
            }
        }
    }
}

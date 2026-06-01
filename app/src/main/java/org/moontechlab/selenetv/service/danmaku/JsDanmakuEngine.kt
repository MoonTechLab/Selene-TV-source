package org.moontechlab.selenetv.service.danmaku

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Context as RhinoContext

/**
 * Rhino 脚本引擎封装。弹幕源的抓取策略以 JS 脚本提供，宿主原语通过全局 `host` 暴露。
 *
 * Android 两个关键点：
 * 1. `optimizationLevel = -1`（解释模式）——否则 Rhino 运行时生成 JVM 字节码，ART 不支持，崩。
 * 2. **不用 LiveConnect**（`Context.javaToJS` 反射暴露 Java 对象）——新版 Rhino 的 JavaMembers
 *    会引用 `javax.lang.model.SourceVersion`，Android 无此类直接崩；改为手动注册 [BaseFunction]。
 *
 * 脚本只在构造时 eval 一次，函数调用复用 scope，[call] 同步。
 */
class JsDanmakuEngine(scriptSource: String, scriptName: String, bridge: DanmakuHostBridge) {

    private val scope: Scriptable

    init {
        val cx = RhinoContext.enter()
        scope = try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            val s = cx.initStandardObjects()
            val host = cx.newObject(s)
            host.put("httpGet", host, fn { a -> bridge.httpGet(str(a, 0), strOrNull(a, 1)) })
            host.put("fetchDanmakuSeg", host, fn { a -> bridge.fetchDanmakuSeg(str(a, 0), strOrNull(a, 1)) })
            host.put("fetchDanmakuXml", host, fn { a -> bridge.fetchDanmakuXml(str(a, 0), strOrNull(a, 1)) })
            host.put("log", host, fn { a -> bridge.log(str(a, 0)); Undefined.instance })
            ScriptableObject.putProperty(s, "host", host)
            cx.evaluateString(s, scriptSource, scriptName, 1, null)
            s
        } finally {
            RhinoContext.exit()
        }
    }

    /** 调用脚本函数（约定返回 JSON 字符串）。函数缺失/返回 undefined → null。 */
    @Synchronized
    fun call(fn: String, vararg args: Any?): String? {
        val cx = RhinoContext.enter()
        try {
            val f = scope.get(fn, scope)
            if (f !is Function) return null
            val result = f.call(cx, scope, scope, args)
            if (result == null || result is Undefined) return null
            return RhinoContext.jsToJava(result, String::class.java) as? String
        } finally {
            RhinoContext.exit()
        }
    }

    private fun fn(block: (Array<out Any?>) -> Any?): BaseFunction = object : BaseFunction() {
        override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? =
            block(args)
    }

    /** JS 参数 → String（非空入参用）。 */
    private fun str(args: Array<out Any?>, i: Int): String =
        if (i < args.size) ScriptRuntime.toString(args[i]) else ""

    /** JS 参数 → String?（null/undefined 透传为 null，用于可选 headersJson）。 */
    private fun strOrNull(args: Array<out Any?>, i: Int): String? {
        if (i >= args.size) return null
        val v = args[i]
        return if (v == null || v is Undefined) null else ScriptRuntime.toString(v)
    }
}

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
            // HTTP
            host.put("httpGet", host, fn { a -> bridge.httpGet(str(a, 0), strOrNull(a, 1)) })
            host.put("httpGetGbk", host, fn { a -> bridge.httpGetGbk(str(a, 0), strOrNull(a, 1)) })
            host.put("httpPost", host, fn { a -> bridge.httpPost(str(a, 0), strOrNull(a, 1), strOrNull(a, 2)) })
            host.put("httpGetBytes", host, fn { a -> bridge.httpGetBytes(str(a, 0), strOrNull(a, 1)) })
            host.put("httpGetFull", host, fn { a -> bridge.httpGetFull(str(a, 0), strOrNull(a, 1)) })
            host.put("httpPostFull", host, fn { a -> bridge.httpPostFull(str(a, 0), strOrNull(a, 1), strOrNull(a, 2)) })
            host.put("cookie", host, fn { a -> bridge.cookie(str(a, 0), str(a, 1)) })
            // 弹幕（B站）
            host.put("fetchDanmakuSeg", host, fn { a -> bridge.fetchDanmakuSeg(str(a, 0), strOrNull(a, 1)) })
            host.put("fetchDanmakuXml", host, fn { a -> bridge.fetchDanmakuXml(str(a, 0), strOrNull(a, 1)) })
            // 解压
            host.put("inflate", host, fn { a -> bridge.inflate(str(a, 0), bool(a, 1)) })
            host.put("gunzip", host, fn { a -> bridge.gunzip(str(a, 0)) })
            // 哈希 / 加密
            host.put("md5", host, fn { a -> bridge.md5(str(a, 0)) })
            host.put("sha1", host, fn { a -> bridge.sha1(str(a, 0)) })
            host.put("hmac", host, fn { a -> bridge.hmac(str(a, 0), str(a, 1), str(a, 2)) })
            host.put("hmacBase64", host, fn { a -> bridge.hmacBase64(str(a, 0), str(a, 1), str(a, 2)) })
            host.put("aesEcbDecrypt", host, fn { a -> bridge.aesEcbDecrypt(str(a, 0), str(a, 1)) })
            host.put("aesCbcDecrypt", host, fn { a -> bridge.aesCbcDecrypt(str(a, 0), str(a, 1), str(a, 2)) })
            host.put("aesCbcDecryptNoPad", host, fn { a -> bridge.aesCbcDecryptNoPad(str(a, 0), str(a, 1), str(a, 2)) })
            host.put("aesCbcEncrypt", host, fn { a -> bridge.aesCbcEncrypt(str(a, 0), str(a, 1), str(a, 2)) })
            host.put("b64decode", host, fn { a -> bridge.b64decode(str(a, 0)) })
            host.put("b64encode", host, fn { a -> bridge.b64encode(str(a, 0)) })
            host.put("protoDecode", host, fn { a -> bridge.protoDecode(str(a, 0)) })
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

    private fun bool(args: Array<out Any?>, i: Int): Boolean =
        if (i < args.size) ScriptRuntime.toBoolean(args[i]) else false
}

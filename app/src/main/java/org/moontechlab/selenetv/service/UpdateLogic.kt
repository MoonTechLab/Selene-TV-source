package org.moontechlab.selenetv.service

import org.moontechlab.selenetv.model.GithubAsset
import org.moontechlab.selenetv.model.GithubRelease
import org.moontechlab.selenetv.model.UpdateInfo

/**
 * 更新相关的纯逻辑：无 Android 依赖，便于单元测试。
 * 负责版本比较、按设备 ABI 选包、以及是否弹窗的判定链。
 */
object UpdateLogic {

    /** 解析版本号为数值段；去掉前导 v/V；任一段非纯数字返回 null。 */
    fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** 数值比较版本；a>b 返回正，a<b 返回负，相等或任一非法返回 0。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a) ?: return 0
        val pb = parseVersion(b) ?: return 0
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * 按设备 ABI 优先级（Build.SUPPORTED_ABIS 顺序）选匹配的 APK 资产。
     * 规则：资产文件名以 .apk 结尾且包含该 ABI 串即命中；按设备优先级取第一个命中。
     * 无任何匹配（资产缺失/CI 构建中/罕见架构）返回 null。
     */
    fun selectAsset(supportedAbis: List<String>, assets: List<GithubAsset>): Pair<String, GithubAsset>? {
        for (abi in supportedAbis) {
            val match = assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.name.contains(abi, ignoreCase = true)
            }
            if (match != null) return abi to match
        }
        return null
    }
}

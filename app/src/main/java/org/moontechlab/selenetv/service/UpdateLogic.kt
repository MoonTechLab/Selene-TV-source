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
}

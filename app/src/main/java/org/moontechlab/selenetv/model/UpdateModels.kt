package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub Release API 资产。 */
@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String = "",
)

/** GitHub Release API 顶层对象（/releases/latest）。 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

/** 判定后得出的领域更新信息：已确认有更新且选好了匹配当前架构的 APK。 */
data class UpdateInfo(
    val version: String,      // 去掉前导 v，如 "1.2.3"
    val title: String,        // release 标题，空则回退到 tag
    val changelog: String,    // release body
    val apkUrl: String,
    val apkSize: Long,
    val abi: String,          // 命中的设备 ABI，如 "arm64-v8a"
)

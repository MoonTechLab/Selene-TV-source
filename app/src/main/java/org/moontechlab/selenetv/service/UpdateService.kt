package org.moontechlab.selenetv.service

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.moontechlab.selenetv.model.GithubRelease
import org.moontechlab.selenetv.model.UpdateInfo
import java.io.File

/**
 * 应用更新服务。阻塞方法，必须在 Dispatchers.IO 调用；失败抛 ApiException。
 * 复用 MediaHttp.httpClient（trust-all），与项目网络约定一致。
 */
object UpdateService {
    private const val TAG = "UpdateService"
    private const val LATEST_URL =
        "https://api.github.com/repos/MoonTechLab/Selene-TV/releases/latest"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 当前 versionName（与 SettingsTab.VersionColophon 一致，不依赖 BuildConfig）。 */
    fun currentVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""

    /**
     * 检查更新。返回 UpdateInfo，或 null（无更新 / CI 构建中无资产 / 无匹配架构）。
     * 网络或解析失败抛 ApiException。
     */
    fun checkForUpdate(context: Context): UpdateInfo? {
        val release = fetchLatestRelease()
        val current = currentVersionName(context)
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        return UpdateLogic.buildUpdateInfo(release, current, abis)
    }

    private fun fetchLatestRelease(): GithubRelease {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SeleneTV")
            .build()
        try {
            MediaHttp.httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("检查更新失败 (${resp.code})", resp.code)
                val body = resp.body?.string() ?: throw ApiException("检查更新失败：空响应")
                return json.decodeFromString<GithubRelease>(body)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatestRelease failed: ${e.message}")
            throw ApiException("检查更新失败：${e.message}")
        }
    }

    /**
     * 下载 APK 到 cacheDir/update/。下载前清空该目录。
     * onProgress 回调 0f..1f；总长未知时回调 -1f。失败抛 ApiException 并删除残文件。
     */
    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "SeleneTV-${info.version}-${info.abi}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "SeleneTV").build()
        try {
            MediaHttp.httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("下载失败 (${resp.code})", resp.code)
                val body = resp.body ?: throw ApiException("下载失败：空响应")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var downloaded = 0L
                        var read = input.read(buf)
                        while (read != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
                            read = input.read(buf)
                        }
                    }
                }
            }
        } catch (e: ApiException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            outFile.delete()
            Log.w(TAG, "downloadApk failed: ${e.message}")
            throw ApiException("下载失败：${e.message}")
        }
        return outFile
    }
}

package org.moontechlab.selenetv.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.moontechlab.selenetv.ui.MoonToast
import java.io.File

/**
 * 通过 FileProvider 拉起系统安装器安装 APK。
 * Android 8.0+ 需「允许安装未知应用」权限：未授予时引导用户去系统设置开启，返回后再次点击安装即可。
 */
object InstallHelper {
    private const val TAG = "InstallHelper"

    /** @return true=已拉起安装器；false=已跳转去授予未知来源权限（或失败），需用户处理后重试。 */
    fun installApk(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure {
                    Log.w(TAG, "open unknown-source settings failed: ${it.message}")
                    MoonToast.show("请在系统设置中允许本应用安装应用")
                }
            return false
        }

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                Log.w(TAG, "start installer failed: ${it.message}")
                MoonToast.show("无法启动安装器")
                false
            }
    }
}

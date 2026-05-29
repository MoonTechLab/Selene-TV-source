package org.moontechlab.selenetv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.UpdateInfo
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.UpdateLogic
import org.moontechlab.selenetv.service.UpdateService
import org.moontechlab.selenetv.ui.AnimatedSelectDialog
import org.moontechlab.selenetv.ui.DialogButton
import org.moontechlab.selenetv.util.InstallHelper
import java.io.File

private enum class Phase { INFO, DOWNLOADING, DONE, ERROR }

/**
 * 启动时挂在全局根（AppNavHost）的更新闸门：拉取→判定→（命中则）弹窗。
 * 自动检查的失败一律静默（仅日志）。每个进程组合一次。
 */
@Composable
fun UpdateGate() {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 仅在已登录 / 本地模式下检查：未登录时首屏是强制登录页，不在其上弹更新框打扰
        if (!AppDataService.isLoggedInSync() && !AppDataService.isLocalModeSync()) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching { UpdateService.checkForUpdate(context) }.getOrNull()
        } ?: return@LaunchedEffect
        val shouldPrompt = UpdateLogic.shouldPrompt(
            info = result,
            skippedVersion = AppDataService.getSkippedVersionSync(),
            postponedUntil = AppDataService.getUpdatePostponedUntilSync(),
            now = System.currentTimeMillis(),
            manual = false,
        )
        if (shouldPrompt) {
            info = result
            visible = true
        }
    }

    info?.let { i ->
        UpdateDialog(
            info = i,
            visible = visible,
            onDismiss = { visible = false },
            onPostpone = {
                AppDataService.setUpdatePostponedUntilSync(
                    System.currentTimeMillis() + UpdateLogic.COOLDOWN_MILLIS,
                )
            },
            onSkip = { AppDataService.setSkippedVersionSync(i.version) },
        )
    }
}

/**
 * 更新弹窗。四态：信息 / 下载中 / 完成 / 错误。
 * 下载中禁止返回键关闭；下载完成自动拉起安装器（未授予未知来源权限时转为「安装」按钮等用户授权后重试）。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPostpone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember { UpdateService.currentVersionName(context) }

    var phase by remember { mutableStateOf(Phase.INFO) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    fun startDownload() {
        phase = Phase.DOWNLOADING
        progress = 0f
        downloadJob = scope.launch {
            try {
                var lastPct = -1
                val file = withContext(Dispatchers.IO) {
                    UpdateService.downloadApk(context, info) { p ->
                        // 节流：只在整数百分比变化时更新 UI 状态，避免每 8KB 一次重组
                        val pct = (p.coerceAtLeast(0f) * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            progress = p.coerceAtLeast(0f)
                        }
                    }
                }
                downloadedFile = file
                phase = Phase.DONE
                InstallHelper.installApk(context, file)
            } catch (e: CancellationException) {
                throw e   // 取消不算错误，phase 已由取消处理重置
            } catch (e: Exception) {
                errorMsg = e.message ?: "下载失败"
                phase = Phase.ERROR
            }
        }
    }

    AnimatedSelectDialog(
        visible = visible,
        onDismiss = { if (phase != Phase.DOWNLOADING) onDismiss() },
    ) { onClose ->
        Box(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1A1A))
                .padding(28.dp),
        ) {
            when (phase) {
                Phase.INFO -> Column {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
                    Text("发现新版本 ${info.version}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 4.dp))
                    Text("当前 $currentVersion", color = Color(0xFF888888), fontSize = 13.sp)
                    Spacer(Modifier.padding(top = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            info.changelog.ifBlank { "（无更新说明）" },
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.padding(top = 20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            text = "立即更新",
                            onClick = { startDownload() },
                            isPrimary = true,
                            modifier = Modifier.weight(1f).focusRequester(fr),
                        )
                        DialogButton(
                            text = "稍后",
                            onClick = { onPostpone(); onClose() },
                            modifier = Modifier.weight(1f),
                        )
                        DialogButton(
                            text = "跳过此版本",
                            onClick = { onSkip(); onClose() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Phase.DOWNLOADING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
                    Text("正在下载更新…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 16.dp))
                    val pct = if (progress > 0f) "${(progress * 100).toInt()}%" else "下载中…"
                    Text(pct, color = Color(0xFF4CAF50), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x22FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (progress > 0f) progress else 0f)
                                .padding(vertical = 4.dp)
                                .background(Color(0xFF4CAF50)),
                        )
                    }
                    Spacer(Modifier.padding(top = 20.dp))
                    DialogButton(
                        text = "取消",
                        onClick = {
                            downloadJob?.cancel()
                            progress = 0f
                            phase = Phase.INFO
                        },
                        modifier = Modifier.width(160.dp).focusRequester(fr),
                    )
                }

                Phase.DONE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
                    Text("下载完成", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            text = "安装",
                            onClick = { downloadedFile?.let { InstallHelper.installApk(context, it) } },
                            isPrimary = true,
                            modifier = Modifier.width(160.dp).focusRequester(fr),
                        )
                        DialogButton(
                            text = "关闭",
                            onClick = onClose,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }

                Phase.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
                    Text("更新失败", color = Color(0xFFE74C3C), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(errorMsg, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    Spacer(Modifier.padding(top = 20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton(
                            text = "重试",
                            onClick = { startDownload() },
                            isPrimary = true,
                            modifier = Modifier.width(160.dp).focusRequester(fr),
                        )
                        DialogButton(
                            text = "关闭",
                            onClick = onClose,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }
            }
        }
    }
}

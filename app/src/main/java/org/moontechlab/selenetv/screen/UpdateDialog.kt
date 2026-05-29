package org.moontechlab.selenetv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
 * 下载中禁止返回键关闭；下载完成进入完成态，由用户点「安装」拉起安装器（首次会引导开启未知来源权限）。
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

    // 焦点种子：把焦点送进「按钮组」容器（focusGroup），让焦点系统自然指派到子按钮——
    // 程序化 requestFocus 直接打在 tv Surface 上虽能获焦却不渲染高亮，经由 group 自然进入才会高亮。
    // 容器稳定存在、按 phase 重新种焦，相位切换后焦点不漏到弹窗背后的内容。
    val buttonsFocus = remember { FocusRequester() }
    var buttonsHaveFocus by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        buttonsHaveFocus = false
        var tries = 0
        while (!buttonsHaveFocus && tries < 15) {
            delay(50)
            runCatching { buttonsFocus.requestFocus() }
            tries++
        }
    }

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
                progress = 1f          // 完成态进度条固定满格，显示 100%
                phase = Phase.DONE
                // 不自动拉起安装器：进入完成态，由用户点「安装」触发（InstallHelper 内部处理未知来源授权）。
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
            // 单一弹窗：标题 + 更新日志始终长显，仅「状态区（进度/错误）」与「按钮区」随相位变化，
            // 避免整块内容相位切换导致的焦点/高亮丢失。
            Column {
                Text("发现新版本 ${info.version}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.padding(top = 4.dp))
                Text("当前 $currentVersion", color = Color(0xFF888888), fontSize = 13.sp)
                Spacer(Modifier.padding(top = 16.dp))

                // 更新日志（始终显示、可滚动）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = if (info.changelog.isBlank()) AnnotatedString("（无更新说明）")
                        else renderChangelog(info.changelog),
                        color = Color(0xFFCCCCCC),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }

                // 状态区：下载进度 / 错误信息（仅相应相位显示）
                when (phase) {
                    // 下载中与完成态都显示进度行；完成态固定 100%（progress 已置 1f）
                    Phase.DOWNLOADING, Phase.DONE -> {
                        Spacer(Modifier.padding(top = 18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0x22FFFFFF)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                                        .background(Color(0xFF4CAF50)),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (progress > 0f) "${(progress * 100).toInt()}%" else "下载中…",
                                color = Color(0xFF4CAF50),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Phase.ERROR -> {
                        Spacer(Modifier.padding(top = 14.dp))
                        Text("更新失败：$errorMsg", color = Color(0xFFE74C3C), fontSize = 13.sp)
                    }
                    else -> {}
                }

                // 按钮区：随相位变化。外层 focusGroup 容器稳定存在；相位切换后由上面的
                // LaunchedEffect 把焦点自然送进组内首个子按钮，触发 tv Surface 的获焦高亮。
                Spacer(Modifier.padding(top = 20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(buttonsFocus)
                        .focusGroup()
                        .onFocusChanged { buttonsHaveFocus = it.hasFocus },
                ) {
                    when (phase) {
                        Phase.INFO -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DialogButton(
                                text = "立即更新",
                                onClick = { startDownload() },
                                isPrimary = true,
                                modifier = Modifier.weight(1f),
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

                        Phase.DOWNLOADING -> DialogButton(
                            text = "取消",
                            onClick = {
                                downloadJob?.cancel()
                                progress = 0f
                                phase = Phase.INFO
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Phase.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DialogButton(
                                text = "安装",
                                onClick = { downloadedFile?.let { InstallHelper.installApk(context, it) } },
                                isPrimary = true,
                                modifier = Modifier.weight(1f),
                            )
                            DialogButton(
                                text = "关闭",
                                onClick = onClose,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Phase.ERROR -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DialogButton(
                                text = "重试",
                                onClick = { startDownload() },
                                isPrimary = true,
                                modifier = Modifier.weight(1f),
                            )
                            DialogButton(
                                text = "关闭",
                                onClick = onClose,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 轻量 Markdown 渲染：只覆盖 release body 常见的简单语法，不做完整解析。
 * - 行首 `#`/`##`/`###` → 加粗标题（仅加粗，不放大字号）
 * - 行首 `- ` / `* ` → 项目符号 `• `
 * - 行内 `**粗体**` → 加粗白色；`` `code` `` → 去掉反引号原样显示
 * 源中的连续空行折叠为单个空行间隔，避免间距过大。
 */
private fun renderChangelog(raw: String): AnnotatedString = buildAnnotatedString {
    val lines = raw.trim().replace("\r\n", "\n").split("\n")
    var emittedAny = false
    var pendingBlank = false
    for (line in lines) {
        val t = line.trim()
        if (t.isBlank()) { pendingBlank = true; continue }
        if (emittedAny) {
            append("\n")
            if (pendingBlank) append("\n") // 源中的空行 → 单个空行间隔（连续空行已折叠）
        }
        pendingBlank = false
        emittedAny = true
        when {
            t.startsWith("#") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                    appendInline(t.trimStart('#').trim())
                }
            t.startsWith("- ") || t.startsWith("* ") -> {
                append("• ")
                appendInline(t.substring(2))
            }
            else -> appendInline(t)
        }
    }
}

/** 处理行内 `**粗体**` 与 `` `code` ``，其余原样追加。 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        val boldStart = text.indexOf("**", i)
        val codeStart = text.indexOf("`", i)
        // 选最近的标记
        val next = listOf(boldStart, codeStart).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(text.substring(i)); return
        }
        append(text.substring(i, next))
        if (next == boldStart) {
            val end = text.indexOf("**", next + 2)
            if (end < 0) { append(text.substring(next)); return }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                append(text.substring(next + 2, end))
            }
            i = end + 2
        } else {
            val end = text.indexOf("`", next + 1)
            if (end < 0) { append(text.substring(next)); return }
            append(text.substring(next + 1, end)) // 去掉反引号，原样显示
            i = end + 1
        }
    }
}

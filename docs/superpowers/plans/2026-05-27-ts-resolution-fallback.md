# TS 字节流兜底分辨率 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优选测速中,当 m3u8 未声明 RESOLUTION 时,从已采样的 `.ts` 字节流中解析出视频宽度作为兜底分辨率。

**Architecture:** 复用 Media3 的 `TsExtractor` 完成 TS 解复用 + SPS 位解码(同时覆盖 H.264 与 HEVC)。在 `SpeedTestService` 内复用测速阶段已下载的第一个分片字节,不发额外网络请求;仅当 m3u8 声明的宽度为 0 时触发兜底。

**Tech Stack:** Kotlin、Media3 (androidx.media3 1.5.1) `extractor` / `extractor.ts`、OkHttp、JUnit(JVM 单测,测纯逻辑)、AndroidJUnit4(instrumented 测,测真实 TS 解析)。

设计文档:`docs/superpowers/specs/2026-05-27-ts-resolution-fallback-design.md`

## 文件结构

- 新增 `app/src/main/java/org/moontechlab/selenetv/service/TsResolutionProbe.kt` — 把一段 TS 字节解析成视频宽度(px),非 TS / 失败返回 0。单一职责。
- 修改 `app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt` — 第一个分片采样时留住字节;`probe()` 在声明宽度为 0 时调用兜底。
- 修改 `gradle/libs.versions.toml` + `app/build.gradle.kts` — 显式声明 `media3-extractor`(原本经 exoplayer 传递引入,显式声明避免版本漂移)。
- 新增 `app/src/test/java/org/moontechlab/selenetv/service/TsResolutionProbeGuardTest.kt` — JVM 单测,覆盖同步字节守卫与 chooseWidth 决策。
- 新增 `app/src/androidTest/java/org/moontechlab/selenetv/service/TsResolutionProbeTest.kt` + `app/src/androidTest/assets/*.ts` — 真实 TS 解析的设备测试。

---

### Task 1: 显式声明 media3-extractor 依赖

**Files:**
- Modify: `gradle/libs.versions.toml:36`
- Modify: `app/build.gradle.kts:119`

- [ ] **Step 1: 在 libs.versions.toml 加 library 别名**

在第 36 行 `androidx-media3-datasource-okhttp = ...` 之后追加一行:

```toml
androidx-media3-extractor = { group = "androidx.media3", name = "media3-extractor", version.ref = "media3" }
```

- [ ] **Step 2: 在 build.gradle.kts 引用它**

在 `app/build.gradle.kts` 第 119 行 `implementation(libs.androidx.media3.datasource.okhttp)` 之后追加:

```kotlin
    implementation(libs.androidx.media3.extractor)
```

- [ ] **Step 3: 验证依赖解析**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep media3-extractor`
Expected: 输出包含 `androidx.media3:media3-extractor:1.5.1`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: 显式声明 media3-extractor 依赖

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: TsResolutionProbe — 同步字节守卫(TDD)+ TsExtractor 解析

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/TsResolutionProbe.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/TsResolutionProbeGuardTest.kt`

JVM 单测只覆盖**纯逻辑**:同步字节守卫,以及 `parseWidth` 对非 TS 字节返回 0(这条路径在触达 `TsExtractor` 之前就返回,故能在普通 JUnit 下运行)。真实 TS 解析在 Task 3 用设备测试验证。

- [ ] **Step 1: 写失败的 JVM 单测**

创建 `app/src/test/java/org/moontechlab/selenetv/service/TsResolutionProbeGuardTest.kt`:

```kotlin
package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsResolutionProbeGuardTest {

    /** 在 0、188、376 偏移放置 TS 同步字节 0x47,守卫应识别为 TS。 */
    @Test
    fun looksLikeTs_recognizesSyncPattern() {
        val bytes = ByteArray(188 * 3)
        bytes[0] = 0x47
        bytes[188] = 0x47
        bytes[376] = 0x47
        assertTrue(TsResolutionProbe.looksLikeTs(bytes))
    }

    @Test
    fun looksLikeTs_rejectsPlaylistText() {
        val m3u8 = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n".toByteArray()
        assertFalse(TsResolutionProbe.looksLikeTs(m3u8))
    }

    @Test
    fun looksLikeTs_rejectsTooShort() {
        assertFalse(TsResolutionProbe.looksLikeTs(ByteArray(10)))
        assertFalse(TsResolutionProbe.looksLikeTs(null))
    }

    /** 非 TS 字节在触达 TsExtractor 前即返回 0,可在 JVM 下运行。 */
    @Test
    fun parseWidth_returnsZeroForNonTs() {
        val m3u8 = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n".toByteArray()
        assertEquals(0, TsResolutionProbe.parseWidth(m3u8))
        assertEquals(0, TsResolutionProbe.parseWidth(null))
    }
}
```

- [ ] **Step 2: 运行测试,确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.service.TsResolutionProbeGuardTest"`
Expected: FAIL — 编译错误,`TsResolutionProbe` 未定义。

- [ ] **Step 3: 实现 TsResolutionProbe.kt**

创建 `app/src/main/java/org/moontechlab/selenetv/service/TsResolutionProbe.kt`:

```kotlin
package org.moontechlab.selenetv.service

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import java.io.EOFException

/**
 * 从一段 MPEG-TS 字节流解析视频宽度(px),用于优选测速在 m3u8 未声明 RESOLUTION 时兜底。
 *
 * 复用 Media3 [TsExtractor]:它完成 TS 解复用 → 定位视频 PID(PMT)→ PES 重组 →
 * 定位并位解码 SPS,**同时支持 H.264(stream_type 0x1B)与 HEVC(0x24)**。
 * 这里只取宽度;评分按宽度分档,不需要其他元数据。
 *
 * 输入通常是测速阶段已下载的第一个 .ts 分片的开头数百 KB —— SPS 必落在第一个 PES 内,
 * 远在该预算之内。非 TS / 解析失败一律返回 0,不抛出。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object TsResolutionProbe {

    private const val TS_PACKET_SIZE = 188
    private const val MAX_READ_STEPS = 50_000  // 防御无限循环;512KB/188 ≈ 2800 包

    /** 返回视频宽度(px);字节不是可解析的 TS 流或解析失败则返回 0。 */
    fun parseWidth(tsBytes: ByteArray?): Int {
        if (!looksLikeTs(tsBytes)) return 0
        return extractWidth(tsBytes!!)
    }

    /** 在前 [TS_PACKET_SIZE] 个偏移里找到一处同步字节 0x47 且 +188 处也是 0x47,即认定为 TS。 */
    internal fun looksLikeTs(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < TS_PACKET_SIZE * 2) return false
        for (start in 0 until TS_PACKET_SIZE) {
            val next = start + TS_PACKET_SIZE
            if (next >= bytes.size) break
            if (bytes[start] == 0x47.toByte() && bytes[next] == 0x47.toByte()) return true
        }
        return false
    }

    /** 用 TsExtractor 跑这段字节,取第一个带宽度的视频轨 Format.width。失败 → 0。 */
    private fun extractWidth(bytes: ByteArray): Int {
        val capture = WidthCapture()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                ProbeTrackOutput(if (type == C.TRACK_TYPE_VIDEO) capture else null)

            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val extractor = TsExtractor()
        extractor.init(output)
        val positionHolder = PositionHolder()
        var input = DefaultExtractorInput(ByteArrayDataReader(bytes), 0, bytes.size.toLong())
        try {
            var steps = 0
            while (capture.width == 0 && steps++ < MAX_READ_STEPS) {
                val result = extractor.read(input, positionHolder)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) {
                    val pos = positionHolder.position
                    if (pos < 0 || pos >= bytes.size) break
                    input = DefaultExtractorInput(ByteArrayDataReader(bytes, pos.toInt()), pos, bytes.size.toLong())
                }
            }
        } catch (_: Throwable) {
            // 截断 / 不可解析:返回已捕获的宽度(通常仍为 0)
        } finally {
            runCatching { extractor.release() }
        }
        return capture.width
    }

    private class WidthCapture {
        var width = 0
    }

    /** DataReader,从内存字节数组按游标顺序读。 */
    private class ByteArrayDataReader(private val data: ByteArray, private var pos: Int = 0) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, target, offset, n)
            pos += n
            return n
        }
    }

    /** 只关心 format();sample 数据读走丢弃以推进 extractor。video 轨捕获首个含宽度的 Format。 */
    private class ProbeTrackOutput(private val capture: WidthCapture?) : TrackOutput {
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) {
            if (capture != null && capture.width == 0 && format.width != Format.NO_VALUE) {
                capture.width = format.width
            }
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            // 无需样本元数据
        }
    }
}
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.service.TsResolutionProbeGuardTest"`
Expected: PASS(4 个用例)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/TsResolutionProbe.kt app/src/test/java/org/moontechlab/selenetv/service/TsResolutionProbeGuardTest.kt
git commit -m "feat: 新增 TsResolutionProbe,从 TS 字节解析视频宽度

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 真实 TS 解析的设备测试(instrumented)

**Files:**
- Create: `app/src/androidTest/assets/h264_720p.ts`
- Create: `app/src/androidTest/assets/hevc_1080p.ts`
- Test: `app/src/androidTest/java/org/moontechlab/selenetv/service/TsResolutionProbeTest.kt`

> Media3 解析类依赖 Android 运行时(SparseArray 等),无法在普通 JVM 单测下运行,故真实解析用 instrumented 测试,**需要已连接的设备/模拟器**。

- [ ] **Step 1: 生成测试用 TS 切片(需 ffmpeg)**

Run:
```bash
mkdir -p app/src/androidTest/assets
ffmpeg -y -f lavfi -i testsrc=size=1280x720:rate=25:duration=2 \
  -c:v libx264 -pix_fmt yuv420p -f mpegts app/src/androidTest/assets/h264_720p.ts
ffmpeg -y -f lavfi -i testsrc=size=1920x1080:rate=25:duration=2 \
  -c:v libx265 -pix_fmt yuv420p -f mpegts app/src/androidTest/assets/hevc_1080p.ts
```
Expected: 两个 `.ts` 文件生成。若本机无 ffmpeg / 无 libx265,可改用任意已知分辨率的真实 `.ts` 切片,并相应调整下方断言的期望宽度。

- [ ] **Step 2: 写 instrumented 测试**

创建 `app/src/androidTest/java/org/moontechlab/selenetv/service/TsResolutionProbeTest.kt`:

```kotlin
package org.moontechlab.selenetv.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TsResolutionProbeTest {

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun parsesH264Width() {
        assertEquals(1280, TsResolutionProbe.parseWidth(asset("h264_720p.ts")))
    }

    @Test
    fun parsesHevcWidth() {
        assertEquals(1920, TsResolutionProbe.parseWidth(asset("hevc_1080p.ts")))
    }

    @Test
    fun nonTsReturnsZero() {
        assertEquals(0, TsResolutionProbe.parseWidth("#EXTM3U\nnot ts\n".toByteArray()))
    }
}
```

- [ ] **Step 3: 运行(需连接设备)**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "org.moontechlab.selenetv.service.TsResolutionProbeTest"`
Expected: PASS(3 个用例);`parsesH264Width`/`parsesHevcWidth` 证明真实 SPS/HEVC-SPS 解析正确。

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/assets/h264_720p.ts app/src/androidTest/assets/hevc_1080p.ts app/src/androidTest/java/org/moontechlab/selenetv/service/TsResolutionProbeTest.kt
git commit -m "test: TsResolutionProbe 真实 H.264/HEVC TS 解析的设备测试

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: chooseWidth 决策(TDD)

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/SpeedTestChooseWidthTest.kt`

把「声明宽度优先,否则用注入的探针解析采样字节」的决策抽成纯函数,可在 JVM 下单测(注入假探针,不触达 TsExtractor / 网络)。

- [ ] **Step 1: 写失败的 JVM 单测**

创建 `app/src/test/java/org/moontechlab/selenetv/service/SpeedTestChooseWidthTest.kt`:

```kotlin
package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestChooseWidthTest {

    @Test
    fun declaredWidthWinsAndSkipsProbe() {
        var probed = false
        val width = SpeedTestService.chooseWidth(1920, byteArrayOf(1, 2, 3)) { probed = true; 720 }
        assertEquals(1920, width)
        assertEquals(false, probed)
    }

    @Test
    fun fallsBackToProbeWhenDeclaredZero() {
        val width = SpeedTestService.chooseWidth(0, byteArrayOf(1, 2, 3)) { 1280 }
        assertEquals(1280, width)
    }

    @Test
    fun returnsZeroWhenNoCapturedBytes() {
        var probed = false
        val width = SpeedTestService.chooseWidth(0, null) { probed = true; 1280 }
        assertEquals(0, width)
        assertEquals(false, probed)
    }
}
```

- [ ] **Step 2: 运行测试,确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.service.SpeedTestChooseWidthTest"`
Expected: FAIL — `chooseWidth` 未定义。

- [ ] **Step 3: 在 SpeedTestService 加 chooseWidth**

在 `SpeedTestService.kt` 的 `score(...)` 函数定义之后(约第 110 行后)插入:

```kotlin
    /**
     * 决定最终采用的宽度:m3u8 已声明(>0)直接用;否则用 [probe] 解析采样到的首分片字节;
     * 无字节则 0。[probe] 注入便于单测(实参为 [TsResolutionProbe.parseWidth]）。
     */
    internal fun chooseWidth(declaredWidth: Int, captured: ByteArray?, probe: (ByteArray) -> Int): Int {
        if (declaredWidth > 0) return declaredWidth
        val bytes = captured ?: return 0
        return probe(bytes)
    }
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.moontechlab.selenetv.service.SpeedTestChooseWidthTest"`
Expected: PASS(3 个用例)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt app/src/test/java/org/moontechlab/selenetv/service/SpeedTestChooseWidthTest.kt
git commit -m "feat: SpeedTestService 加 chooseWidth 决策(声明优先,否则探针兜底)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 采样首分片字节并接入 probe()

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt:90-217`

让 `sampleSegmentBytes` 在测第一个分片时顺手留住字节;`measureDownloadSpeed` 透出该字节;`probe()` 用 `chooseWidth` 在声明宽度为 0 时兜底。无新增网络请求。

- [ ] **Step 1: 替换 probe()(约第 90-104 行)**

把现有 `probe` 函数整体替换为:

```kotlin
    /** 解析单条 m3u8 的分辨率与下载速度。任何环节异常 → FAILED。 */
    private suspend fun probe(streamUrl: String): SourceSpeed = withContext(Dispatchers.IO) {
        val content = getText(streamUrl) ?: return@withContext SourceSpeed(state = SpeedTestState.FAILED)
        val segments = parseSegments(content, streamUrl)
        if (segments.isEmpty()) return@withContext SourceSpeed(state = SpeedTestState.FAILED)

        val declaredWidth = parseResolutionWidth(content)
        val sample = measureDownloadSpeed(segments)
        // m3u8 未声明分辨率时,从已采样的首分片 TS 字节兜底解析
        val widthPx = chooseWidth(declaredWidth, sample.firstSegmentBytes, TsResolutionProbe::parseWidth)
        SourceSpeed(
            state = SpeedTestState.DONE,
            widthPx = widthPx,
            speedKbps = sample.speedKbps,
            quality = qualityLabel(widthPx),
            speedLabel = speedLabel(sample.speedKbps),
        )
    }
```

- [ ] **Step 2: 替换 measureDownloadSpeed()(约第 189-200 行)**

把现有 `measureDownloadSpeed` 函数整体替换为(同时新增 `SpeedSample` 数据类):

```kotlin
    /** 测速结果:平均下载速度 + 首分片采样字节(供分辨率兜底,可能为 null)。 */
    private data class SpeedSample(val speedKbps: Double, val firstSegmentBytes: ByteArray?)

    /** 单分片采样结果:采样到的字节数 + 仅首分片留住的字节内容。 */
    private data class SegmentSample(val bytes: Int, val captured: ByteArray? = null)

    /** 并发采样前 [SEGMENT_SAMPLE] 个分片,总采样字节 / 总耗时 = KB/s;首分片字节留作兜底。 */
    private suspend fun measureDownloadSpeed(segments: List<String>): SpeedSample = coroutineScope {
        val sample = segments.take(SEGMENT_SAMPLE)
        val start = System.currentTimeMillis()
        val results = sample.mapIndexed { index, url ->
            async(Dispatchers.IO) { sampleSegmentBytes(url, capture = index == 0) }
        }.awaitAll()
        val elapsedMs = System.currentTimeMillis() - start
        val totalBytes = results.sumOf { it.bytes }
        val firstSegmentBytes = results.firstOrNull()?.captured
        val speedKbps =
            if (totalBytes <= 0 || elapsedMs <= 0) 0.0
            else (totalBytes / 1024.0) / (elapsedMs / 1000.0)
        SpeedSample(speedKbps, firstSegmentBytes)
    }
```

- [ ] **Step 3: 替换 sampleSegmentBytes()(约第 202-217 行)**

把现有 `sampleSegmentBytes` 函数整体替换为:

```kotlin
    /**
     * 流式读取分片,至多 [SEGMENT_BYTE_BUDGET] 字节即停(关闭 body 中断剩余下载)。
     * [capture] 为 true 时(仅首分片)同时把读到的字节留存,供分辨率兜底解析。
     */
    private suspend fun sampleSegmentBytes(url: String, capture: Boolean): SegmentSample {
        val resp = execute(request(url)) ?: return SegmentSample(0)
        return resp.use {
            if (!it.isSuccessful) return@use SegmentSample(0)
            val stream = it.body?.byteStream() ?: return@use SegmentSample(0)
            val buf = ByteArray(16 * 1024)
            val sink = if (capture) java.io.ByteArrayOutputStream(SEGMENT_BYTE_BUDGET) else null
            var total = 0
            while (total < SEGMENT_BYTE_BUDGET) {
                val n = stream.read(buf)
                if (n < 0) break
                sink?.write(buf, 0, n)
                total += n
            }
            SegmentSample(total, sink?.toByteArray())
        }
    }
```

- [ ] **Step 4: 编译并跑全部单测,确认无回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL,所有既有测试 + 新增 `TsResolutionProbeGuardTest` / `SpeedTestChooseWidthTest` 通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt
git commit -m "feat: 优选测速接入 TS 字节兜底分辨率,复用首分片采样字节

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 整体构建与设备验收

**Files:** 无(验证)

- [ ] **Step 1: Release 构建检查 R8(TsExtractor 反射/规则)**

Run: `./gradlew lintDebug assembleDebug`
Expected: BUILD SUCCESSFUL,无新增 lint error。

- [ ] **Step 2: 安装到设备并启动**

Run: `./gradlew installDebug`
然后启动 MainActivity(`adb shell am start -n org.moontechlab.selenetv/.MainActivity`)。

- [ ] **Step 3: 真机验收(交回用户)**

在详情页找一个 **m3u8 不声明 RESOLUTION** 的源,触发「测速优选」:此前该源分辨率档位为空,现应显示出实际档位(如 720p/1080p),且参与排序。请用户在设备上确认。

---

## 备注

- `chooseWidth` 注入 `probe` 函数是为了 JVM 可测;真实调用传 `TsResolutionProbe::parseWidth`。
- `SpeedSample` / `SegmentSample` 为 `private` 实现细节,不进入对外模型;`SourceSpeed`、`score()`、UI 均不变。
- `media3.model` 序列化与 proguard 无关本改动(无新增 `@Serializable` model)。

---

## 追加:AES-128 加密 HLS 支持(实测多数无 RESOLUTION 源为加密分片)

测速下载到的是密文,需在喂 `TsExtractor` 前解密。详见设计文档「附录:AES-128 加密 HLS 支持」。
`javax.crypto` 在普通 JVM 单测下可运行,故解密与 EXT-X-KEY 解析均可 TDD。

### Task 7: HlsDecryptor — EXT-X-KEY 解析 + AES-128-CBC 解密(TDD)

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/service/HlsDecryptor.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/service/HlsDecryptorTest.kt`

纯逻辑(无 Android、无网络),全部 JVM 单测覆盖。`parseKeyTag` 解析 `#EXT-X-KEY` 与
`#EXT-X-MEDIA-SEQUENCE`;`resolveIv` 派生 IV;`decryptCbc` 做 `AES/CBC/NoPadding` 解密。

- [ ] Step 1–4:对以下三组行为写失败测试 → 实现 → 通过:
  - `decryptCbc(cipher,key,iv)` 往返:用 `javax.crypto.Cipher("AES/CBC/NoPadding")` 加密一段
    已知明文(长度补足 16 倍数),`decryptCbc` 解密后 == 原文;并验证只解前缀整块也正确。
  - `parseKeyTag(content, baseUrl)`:含 `#EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x...,` 的媒体
    播放列表 → 返回 `method=AES_128`、`keyUri` 为相对 URI 对 `baseUrl` 解析后的绝对地址、`ivHex` 原样、
    `mediaSequence` 取自 `#EXT-X-MEDIA-SEQUENCE`(缺省 0);无 EXT-X-KEY → null;`METHOD=NONE` → `method=NONE`。
  - `resolveIv(ivHex, mediaSequence)`:`ivHex="0x000…01"` → 对应 16 字节;`ivHex=null, mediaSequence=5`
    → 16 字节大端,末字节 0x05。
- [ ] Step 5:Commit `feat: 新增 HlsDecryptor(EXT-X-KEY 解析 + AES-128-CBC 解密)`。

实现要点:
```kotlin
object HlsDecryptor {
    enum class Method { NONE, AES_128, UNSUPPORTED }
    data class KeyTag(val method: Method, val keyUri: String?, val ivHex: String?, val mediaSequence: Long)

    fun parseKeyTag(content: String, baseUrl: String): KeyTag?   // 无 #EXT-X-KEY → null
    fun resolveIv(ivHex: String?, mediaSequence: Long): ByteArray // 16 字节
    fun decryptCbc(cipher: ByteArray, key: ByteArray, iv: ByteArray): ByteArray // AES/CBC/NoPadding,截到 16 整块
}
```
`decryptCbc` 用 `Cipher.getInstance("AES/CBC/NoPadding")`、`SecretKeySpec(key,"AES")`、`IvParameterSpec(iv)`,
对 `cipher.copyOf((cipher.size/16)*16)` 解密。`resolveIv` 解析 `0x` 前缀十六进制为 16 字节,或把
`mediaSequence` 写入 16 字节数组的低 8 字节(大端)。`parseKeyTag` 用与现有 `resolveUrl` 一致的相对 URI 解析。

### Task 8: probe() 兜底分支接入解密

**Files:**
- Modify: `app/src/main/java/org/moontechlab/selenetv/service/SpeedTestService.kt`

- [ ] Step 1:新增 `getBytes(url)` 拉密钥(复用 `execute`/`request`):
```kotlin
    private suspend fun getBytes(url: String): ByteArray? {
        val resp = execute(request(url)) ?: return null
        return resp.use { if (it.isSuccessful) it.body?.bytes() else null }
    }
```
- [ ] Step 2:新增私有 `decryptFirstSegmentIfNeeded`,并改 `probe()` 兜底分支先解密再探测:
```kotlin
    /** 加密 HLS:解密首分片密文为明文 TS;非加密 / 不支持 / 失败 → 原样返回。 */
    private suspend fun decryptFirstSegmentIfNeeded(content: String, baseUrl: String, cipher: ByteArray): ByteArray {
        val tag = HlsDecryptor.parseKeyTag(content, baseUrl) ?: return cipher
        if (tag.method != HlsDecryptor.Method.AES_128 || tag.keyUri == null) return cipher
        val key = getBytes(tag.keyUri) ?: return cipher
        if (key.size != 16) return cipher
        val iv = HlsDecryptor.resolveIv(tag.ivHex, tag.mediaSequence)
        return runCatching { HlsDecryptor.decryptCbc(cipher, key, iv) }.getOrDefault(cipher)
    }
```
  `probe()` 兜底分支(替换原 `chooseWidth(declaredWidth, sample.firstSegmentBytes, ...)`):
```kotlin
        val widthPx = if (declaredWidth > 0) {
            declaredWidth
        } else {
            val cipher = sample.firstSegmentBytes
            val plain = if (cipher == null) null else decryptFirstSegmentIfNeeded(content, streamUrl, cipher)
            chooseWidth(0, plain, TsResolutionProbe::parseWidth)
        }
```
- [ ] Step 3:`./gradlew :app:testDebugUnitTest` 全绿。
- [ ] Step 4:Commit `feat: 优选测速兜底解密 AES-128 加密分片后再探测分辨率`。

### Task 3(修订): 设备端明文 TS 解析验证

用户提供的 `.ts` 为加密分片,无法直接作明文解析样本。改为:**需用户额外提供该分片对应的
密钥与 IV(或其 m3u8 URL)**,据此离线解密生成明文 `.ts` 提交为 instrumented 资产并断言宽度;
或直接提供一个未加密的 `.ts`。在拿到前 Task 3 维持 PARKED。

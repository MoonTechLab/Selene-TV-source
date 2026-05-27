package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 播放源测速优选。自 mobile 端 `lib/services/m3u8_service.dart` 迁移。
 *
 * 流程：为每个源取一条试播链接（优先第二集，更具代表性）→ 解析其 m3u8 → 并发测
 * 「分辨率」与「下载速度」→ 全部完成后按分辨率(50%) + 速度(50%) 综合打分排序。
 *
 * 与原版差异：**不测延迟**。对视频流（缓冲后顺序播放）而言 RTT 几乎不影响观感，
 * 去掉延迟后测速更快，权重也由 40/40/20 调整为 50/50。
 *
 * 资源约束（避免内存峰值 / 线程占用）：
 * - 下载速度只**流式采样固定字节**（[SEGMENT_BYTE_BUDGET]），不缓冲整段 .ts；读够即关闭、中断剩余下载。
 * - 源级并发上限 [MAX_CONCURRENT_SOURCES]，避免一次性扇出「源数 × 3」个整段下载。
 * - 测速 client 设 [CALL_TIMEOUT_MS] 硬上限；网络请求经 [execute] 走 enqueue，协程取消即 `call.cancel()`，
 *   用户停止 / 离开详情页能立即放手，不会在后台继续下载。
 *
 * 网络一律走 [MediaHttp.httpClient]（信任所有证书 / 跳过 hostname 校验），适配自签后端。
 */
object SpeedTestService {

    private const val SEGMENT_SAMPLE = 3              // 取前 N 个分片测速
    private const val SEGMENT_BYTE_BUDGET = 512 * 1024 // 每分片最多采样字节数（512KB）
    private const val MAX_CONCURRENT_SOURCES = 6      // 源级并发上限
    private const val PER_SOURCE_TIMEOUT_MS = 8000L   // 单源协程超时
    private const val CALL_TIMEOUT_MS = 8000L         // 单次 HTTP 调用硬上限
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    private const val DEFAULT_MAX_SPEED = 1024.0      // 无有效速度时的基准（1MB/s）

    /** 复用 MediaHttp 的连接池 / SSL，仅追加 callTimeout 硬上限。 */
    private val client by lazy {
        MediaHttp.httpClient.newBuilder()
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 限制同时进行测速的源数量。 */
    private val sourceGate = Semaphore(MAX_CONCURRENT_SOURCES)

    /** 选要测的链接：优先第二集，否则第一集，无集数返回 null。 */
    fun testUrl(source: SearchResult): String? = when {
        source.episodes.size >= 2 -> source.episodes[1]
        source.episodes.isNotEmpty() -> source.episodes[0]
        else -> null
    }

    /**
     * 并发测速全部源。每个源测完（成功或失败）立即通过 [onResult] 回调，便于 UI 实时出分。
     * 挂起直到全部完成；调用方取消协程即中断测速（已出的结果保留，在途下载即时放手）。
     */
    suspend fun testAll(
        sources: List<SearchResult>,
        onResult: (key: String, result: SourceSpeed) -> Unit,
    ) = coroutineScope {
        sources.mapNotNull { source ->
            val url = testUrl(source) ?: return@mapNotNull null
            val key = "${source.source}+${source.id}"
            async(Dispatchers.IO) {
                val result = sourceGate.withPermit {
                    withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) { probe(url) }
                } ?: SourceSpeed(state = SpeedTestState.FAILED)
                onResult(key, result)
            }
        }.awaitAll()
    }

    /** 解析单条 m3u8 的分辨率与下载速度。任何环节异常 → FAILED。 */
    private suspend fun probe(streamUrl: String): SourceSpeed = withContext(Dispatchers.IO) {
        val content = getText(streamUrl) ?: return@withContext SourceSpeed(state = SpeedTestState.FAILED)
        val segments = parseSegments(content, streamUrl)
        if (segments.isEmpty()) return@withContext SourceSpeed(state = SpeedTestState.FAILED)

        val declaredWidth = parseResolutionWidth(content)
        val sample = measureDownloadSpeed(segments)
        // m3u8 未声明分辨率时,从首分片 TS 字节兜底解析;加密 HLS 先解密再探测
        val widthPx = if (declaredWidth > 0) {
            declaredWidth
        } else {
            val segmentBytes = sample.firstSegmentBytes
            val plain = if (segmentBytes == null) null else decryptFirstSegmentIfNeeded(content, streamUrl, segmentBytes)
            chooseWidth(0, plain, TsResolutionProbe::parseWidth)
        }
        SourceSpeed(
            state = SpeedTestState.DONE,
            widthPx = widthPx,
            speedKbps = sample.speedKbps,
            quality = qualityLabel(widthPx),
            speedLabel = speedLabel(sample.speedKbps),
        )
    }

    /** 综合分：分辨率 50% + 速度 50%，speed 按本批 [maxSpeed] 线性映射。失败源记 0。 */
    fun score(s: SourceSpeed, maxSpeed: Double): Double {
        if (s.state != SpeedTestState.DONE) return 0.0
        return qualityScore(s.widthPx) * 0.5 + speedScore(s.speedKbps, maxSpeed) * 0.5
    }

    /** 排序键:DONE=score(0..100)、TESTING/null=-1、FAILED=-2。详情页/播放页统一重排用。 */
    fun rank(result: SourceSpeed?, maxSpeed: Double): Double = when {
        result == null || result.state == SpeedTestState.TESTING -> -1.0
        result.state == SpeedTestState.FAILED -> -2.0
        else -> score(result, maxSpeed)
    }

    /** 角标文案:TESTING→「测速中」、FAILED→「超时」、DONE→「quality · speedLabel」(皆空→「未知」)。 */
    fun badgeLabel(speed: SourceSpeed): String = when (speed.state) {
        SpeedTestState.TESTING -> "测速中"
        SpeedTestState.FAILED -> "超时"
        SpeedTestState.DONE ->
            listOf(speed.quality, speed.speedLabel).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "未知" }
    }

    /**
     * 决定最终采用的宽度:m3u8 已声明(>0)直接用;否则用 [probe] 解析采样到的首分片字节;
     * 无字节则 0。[probe] 注入便于单测(实参为 [TsResolutionProbe.parseWidth]）。
     */
    internal fun chooseWidth(declaredWidth: Int, captured: ByteArray?, probe: (ByteArray) -> Int): Int {
        if (declaredWidth > 0) return declaredWidth
        val bytes = captured ?: return 0
        return probe(bytes)
    }

    /** 加密 HLS:解密首分片密文为明文 TS;非加密 / 不支持 / 失败 → 原样返回(探针随后得 0)。 */
    private suspend fun decryptFirstSegmentIfNeeded(content: String, baseUrl: String, cipher: ByteArray): ByteArray {
        val tag = HlsDecryptor.parseKeyTag(content, baseUrl) ?: return cipher
        if (tag.method != HlsDecryptor.Method.AES_128 || tag.keyUri == null) return cipher
        val key = getBytes(tag.keyUri) ?: return cipher
        if (key.size != 16) return cipher
        val iv = HlsDecryptor.resolveIv(tag.ivHex, tag.mediaSequence)
        return runCatching { HlsDecryptor.decryptCbc(cipher, key, iv) }.getOrDefault(cipher)
    }

    /** 本批有效下载速度的最大值，作为速度评分的线性映射基准。 */
    fun maxSpeed(results: Collection<SourceSpeed>): Double =
        results.filter { it.state == SpeedTestState.DONE && it.speedKbps > 0 }
            .maxOfOrNull { it.speedKbps } ?: DEFAULT_MAX_SPEED

    // --- 评分子项 ---

    private fun qualityScore(widthPx: Int): Double = when {
        widthPx >= 3840 -> 100.0  // 4K
        widthPx >= 2560 -> 85.0   // 2K
        widthPx >= 1920 -> 75.0   // 1080p
        widthPx >= 1280 -> 60.0   // 720p
        widthPx >= 854 -> 40.0    // 480p
        widthPx >= 640 -> 20.0    // 360p
        else -> 0.0               // 未知
    }

    private fun speedScore(speedKbps: Double, maxSpeed: Double): Double {
        if (speedKbps <= 0) return 30.0  // 无有效速度给默认分
        return ((speedKbps / maxSpeed) * 100.0).coerceIn(0.0, 100.0)
    }

    // --- 展示文案 ---

    private fun qualityLabel(widthPx: Int): String = when {
        widthPx >= 3840 -> "4K"
        widthPx >= 2560 -> "2K"
        widthPx >= 1920 -> "1080p"
        widthPx >= 1280 -> "720p"
        widthPx >= 854 -> "480p"
        widthPx >= 640 -> "360p"
        else -> ""
    }

    private fun speedLabel(speedKbps: Double): String = when {
        speedKbps <= 0 -> ""
        speedKbps >= 1024 -> "%.1fMB/s".format(speedKbps / 1024)
        else -> "%.1fKB/s".format(speedKbps)
    }

    // --- m3u8 解析与测速 ---

    /** 非注释非空行即视为分片 URL（与 mobile 一致）。 */
    private fun parseSegments(content: String, baseUrl: String): List<String> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveUrl(it, baseUrl) }
            .toList()

    /** 扫描 #EXT-X-STREAM-INF 的 RESOLUTION，取宽度；无则 0。 */
    private fun parseResolutionWidth(content: String): Int {
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val params = line.removePrefix("#EXT-X-STREAM-INF:").split(",")
            for (part in params) {
                val kv = part.split("=", limit = 2)
                if (kv.size == 2 && kv[0].trim().equals("RESOLUTION", ignoreCase = true)) {
                    val w = kv[1].trim().substringBefore("x").toIntOrNull()
                    if (w != null) return w
                }
            }
        }
        return 0
    }

    /** 相对 URL 解析为绝对 URL。 */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (_: Exception) {
            url
        }
    }

    /** 测速结果:平均下载速度 + 首分片采样字节(供分辨率兜底,可能为 null)。 */
    private class SpeedSample(val speedKbps: Double, val firstSegmentBytes: ByteArray?)

    /** 单分片采样结果:采样到的字节数 + 仅首分片留住的字节内容。 */
    private class SegmentSample(val bytes: Int, val captured: ByteArray? = null)

    /** 并发采样前 [SEGMENT_SAMPLE] 个分片,总采样字节 / 总耗时 = KB/s;首分片字节留作兜底。 */
    private suspend fun measureDownloadSpeed(segments: List<String>): SpeedSample = coroutineScope {
        val sample = segments.take(SEGMENT_SAMPLE)
        val start = System.currentTimeMillis()
        val results = sample.mapIndexed { index, url ->
            async(Dispatchers.IO) { sampleSegmentBytes(url, capture = index == 0) }
        }.awaitAll()
        val elapsedMs = System.currentTimeMillis() - start
        val totalBytes = results.sumOf { it.bytes }
        // awaitAll 保持列表顺序,results[0] 即首个分片
        val firstSegmentBytes = results.firstOrNull()?.captured
        val speedKbps =
            if (totalBytes <= 0 || elapsedMs <= 0) 0.0
            else (totalBytes / 1024.0) / (elapsedMs / 1000.0)
        SpeedSample(speedKbps, firstSegmentBytes)
    }

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

    private suspend fun getText(url: String): String? {
        val resp = execute(request(url)) ?: return null
        return resp.use { if (it.isSuccessful) it.body?.string() else null }
    }

    private suspend fun getBytes(url: String): ByteArray? {
        val resp = execute(request(url)) ?: return null
        return resp.use { if (it.isSuccessful) it.body?.bytes() else null }
    }

    /**
     * enqueue 方式发起调用：协程取消时 `call.cancel()` 立即中断（连 connect/读取一并放手）。
     * 失败或取消返回 null。callTimeout 仍是兜底硬上限，覆盖随后的 body 读取。
     */
    private suspend fun execute(req: Request): Response? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept", "*/*")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .build()
}

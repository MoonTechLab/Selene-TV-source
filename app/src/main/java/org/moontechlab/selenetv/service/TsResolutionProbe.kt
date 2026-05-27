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
import androidx.media3.extractor.text.SubtitleParser
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
        val extractor = TsExtractor(SubtitleParser.Factory.UNSUPPORTED)
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

    /**
     * 只关心 format();sample 数据读走丢弃以推进 extractor。video 轨捕获首个含宽度的 Format。
     *
     * 注:Media3 1.5.1 中 TrackOutput 增加了 part 参数的抽象重载:
     *   sampleData(DataReader, Int, Boolean, Int) — 第四个参数为 @SampleDataPart Int
     *   sampleData(ParsableByteArray, Int, Int)   — 第三个参数为 @SampleDataPart Int
     * 无 part 参数的两个重载在 1.5.1 中已成为 default 方法(非 abstract),故不需要实现。
     */
    private class ProbeTrackOutput(private val capture: WidthCapture?) : TrackOutput {
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) {
            if (capture != null && capture.width == 0 && format.width != Format.NO_VALUE) {
                capture.width = format.width
            }
        }

        // Media3 1.5.1 abstract: sampleData(DataReader, Int, Boolean, Int)
        // @SampleDataPart targets type usage; in Kotlin it goes on the type, not the parameter name.
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: @TrackOutput.SampleDataPart Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
            return n
        }

        // Media3 1.5.1 abstract: sampleData(ParsableByteArray, Int, Int)
        override fun sampleData(data: ParsableByteArray, length: Int, part: @TrackOutput.SampleDataPart Int) {
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

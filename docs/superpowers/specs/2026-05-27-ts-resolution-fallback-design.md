# 从 TS 字节流兜底解析分辨率(优选测速)

- 日期:2026-05-27
- 状态:已确认设计,待实现计划
- 涉及模块:`service/SpeedTestService.kt`、新增 `service/TsResolutionProbe.kt`

## 背景与问题

优选测速(`SpeedTestService`)按「分辨率 50% + 下载速度 50%」给每个播放源打分。
分辨率目前只来自 m3u8 里 `#EXT-X-STREAM-INF` 的 `RESOLUTION` 字段
(`parseResolutionWidth`)。部分自托管源**不声明** `RESOLUTION`,导致 `widthPx == 0`,
分辨率项拿 0 分,优选结果失真。

分辨率本身编码在视频基本流的 SPS(H.264)/ SPS+VPS(HEVC)里,与 m3u8 是否声明无关。
因此可以从 `.ts` 字节流中兜底解析出真实分辨率。

## 目标

- 当 m3u8 未声明 `RESOLUTION`(`widthPx == 0`)时,从已采样的 `.ts` 字节中解析出视频宽度作为兜底。
- 覆盖 **H.264 与 HEVC** 两种编码。
- **不增加额外网络请求**:复用测速阶段已下载的第一个分片字节。
- 对现有评分、模型、UI、网络层零改动。

## 非目标(范围外)

- master 列表**无 RESOLUTION** 时「下钻第一个 variant 播放列表再取 `.ts`」的处理。
  这种形态较少且需多发请求,本期不做;遇到时按「字节不是 TS」优雅返回 0。
- 高度 / 帧率 / 码率等其他元数据。只取 `width`,因为评分按宽度分档。
- master 列表带 RESOLUTION 时测速实际下载的是 variant 播放列表(非 `.ts`)这一既有问题,
  本期不涉及。

## 方案:复用 Media3 `TsExtractor`(Approach A)

Media3 已经是依赖(经 `media3-exoplayer-hls`)。`androidx.media3.extractor.ts.TsExtractor`
完整实现了 TS 解复用 → 定位视频 PID(PMT)→ PES 重组 → 定位并位解码 SPS 的整条流水线,
**同时支持 H.264(`stream_type 0x1B`)与 HEVC(`0x24`)**。复用它,只需写一层内存适配。

### 接入点:`SpeedTestService.probe()`

兜底仅在 `widthPx == 0` 时触发,复用 `measureDownloadSpeed` 已下载的字节:

```kotlin
val declaredWidth = parseResolutionWidth(content)
val (speedKbps, firstSegmentBytes) = measureDownloadSpeed(segments) // 额外返回 segment[0] 采样字节
val widthPx = if (declaredWidth > 0) declaredWidth
              else TsResolutionProbe.parseWidth(firstSegmentBytes)   // 非 TS / 解析失败 → 0
```

`widthPx` 继续喂给现有 `qualityScore` / `qualityLabel`,评分逻辑不变。

### 字节捕获:不发第二次请求

`sampleSegmentBytes` 现在分块读、只计数。改动:

- 读**第一个分片**时,顺便留住开头那段字节,上限沿用现有 `SEGMENT_BYTE_BUDGET`(512KB);
  反正都要下载,SPS 必落在第一个 PES 内,远在该预算之内。
- 其余分片仍只计数。
- 返回值改为小结构(如 `data class SampleResult(val bytes: Int, val captured: ByteArray? = null)`),
  由 `measureDownloadSpeed` 把 `segment[0]` 的 `captured` 透出给 `probe`。
- 内存峰值:6 并发 × ≤512KB ≈ 3MB,解析完即释放。

### 新文件:`service/TsResolutionProbe.kt`

无状态 `object`,单一入口:

```kotlin
@OptIn(UnstableApi::class)
object TsResolutionProbe {
    /** 返回视频宽度(px);字节不是可解析的 TS 流则返回 0。 */
    fun parseWidth(tsBytes: ByteArray?): Int
}
```

内部逻辑:

1. 快速保护:`tsBytes` 为空,或未按 TS 节奏出现同步字节 `0x47`,直接返回 0
   (覆盖「采样到的其实是嵌套播放列表而非 `.ts`」)。
2. 用内存适配层驱动 `TsExtractor` 跑这段字节:
   - 基于 `tsBytes` 的游标 `DataReader`,包装成 `DefaultExtractorInput`;
   - 捕获型 `ExtractorOutput`,其 `TrackOutput` 记录第一个带视频 `width` 的 `format(Format)`;
     `sampleData` 等回调读走并丢弃字节以推进 extractor。
   - 循环 `extractor.read(input, positionHolder)`,直到拿到视频 `Format`、
     返回 `RESULT_END_OF_INPUT`、或字节读完。
3. 返回该 `Format.width`。任何异常 → 0。

纯 CPU、亚毫秒级,且只对未声明 RESOLUTION 的源运行。

## 边界情况

- **媒体播放列表、直挂 `.ts`、无 RESOLUTION**:主力场景,直接命中,采样字节即真 TS。
- **字节不是 TS**(如 master 列表行是 variant `.m3u8`):同步字节保护返回 0,与当前行为一致。
- **解析失败 / SPS 被截断**:返回 0,无回退;`FAILED` / 超时路径不动。
- **master 列表带 RESOLUTION**:`widthPx > 0`,兜底永不触发。

## 不改动的部分

`SourceSpeed` 模型、`score()` 与 50/50 权重、`DetailScreen` / `DetailSourcesSection` UI、
`MediaHttp` 网络客户端、超时与并发上限,全部不变。

## 影响面

- 新增:`service/TsResolutionProbe.kt`。
- 修改:`SpeedTestService.probe()` / `measureDownloadSpeed()` / `sampleSegmentBytes()`(局部)。

## 验证思路

- 单测:对一段已知分辨率的 H.264 TS 字节、一段 HEVC TS 字节,`parseWidth` 返回正确宽度;
  对随机 / 非 TS 字节返回 0。
- 设备:找一个不声明 RESOLUTION 的真实源,确认优选测速能给出分辨率档位(此前为空)。

## 附录:AES-128 加密 HLS 支持(2026-05-27 追加)

实测用户的「不声明分辨率」源多为 **AES-128 加密 HLS**:分片首字节非 `0x47`、前 64KB
熵≈7.997 bits/byte、文件大小为 16 的整数倍而非 188 的整数倍 —— 典型 AES-CBC 密文。
测速阶段下载到的是密文,直接喂 `TsExtractor` 必然失败(`looksLikeTs` 判否 → 0)。
ExoPlayer 播放时透明解密,本探针需自行解密。

### 行为

- 仅当 `declaredWidth == 0`(走兜底)时,才解析加密信息并解密;声明了 RESOLUTION 不受影响。
- 解析媒体播放列表中的 `#EXT-X-KEY:METHOD=AES-128,URI="...",IV=0x...`:
  - `METHOD=NONE` 或无该标签 → 视为明文,首分片字节原样交给探针。
  - `METHOD=AES-128` → 拉取密钥(16 字节,经 [MediaHttp.httpClient],**多一次密钥请求**),
    按 RFC 8216 确定 IV:有 `IV` 属性用之(16 字节十六进制);无则用首分片的媒体序号
    (`#EXT-X-MEDIA-SEQUENCE`,缺省 0)的 128-bit 大端表示。
  - 对采样到的首分片密文做 **AES-128-CBC**(`AES/CBC/NoPadding`)解密:仅解密整 16 字节块
    (`floor(len/16)*16`),丢弃尾部不足一块的部分;SPS 远在分片开头,无需处理 PKCS7 尾块填充。
  - 其他 METHOD(如 `SAMPLE-AES`)不支持 → 原样返回密文(结果 0,无回退)。
  - 任意环节失败(密钥拉取失败、长度非 16、解密异常)→ 原样返回密文 → 探针得 0,不崩。

### 新增/改动

- 新增 `service/HlsDecryptor.kt`(`object`):`parseKeyTag(content, baseUrl)` 解析 EXT-X-KEY +
  媒体序号(纯函数);`resolveIv(ivHex?, mediaSequence)` 派生 IV(纯函数);
  `decryptCbc(cipher, key, iv)` AES-128-CBC 解密(`javax.crypto`,纯 JVM 可测)。
- `SpeedTestService`:新增 `getBytes(url)` 拉密钥;`probe()` 兜底分支在 `parseWidth` 前先解密。

### 测试

- JVM 单测(`javax.crypto` 在普通 JUnit 下可运行):
  - `decryptCbc` 往返:已知明文加密后解密 == 原文;前缀(整块)解密正确。
  - `parseKeyTag`:含 EXT-X-KEY 的 m3u8 → 正确 method/解析后 URI/IV;无标签 → null;`METHOD=NONE`。
  - `resolveIv`:有 IV 属性用属性值;无则媒体序号大端 16 字节。
- 设备 instrumented:用解密后得到的明文 TS 验证 `TsExtractor` 取到正确宽度
  (需要密钥/IV 才能离线解密生成明文样本)。

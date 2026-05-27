package org.moontechlab.selenetv.service

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 解析 HLS 媒体播放列表的 AES-128 加密信息并解密分片，用于优选测速对加密源兜底取分辨率。
 * 纯逻辑（无网络、无 Android）；密钥拉取由调用方完成。
 */
object HlsDecryptor {

    enum class Method { NONE, AES_128, UNSUPPORTED }

    data class KeyTag(
        val method: Method,
        val keyUri: String?,     // 已对 baseUrl 解析为绝对地址（method=AES_128 时）
        val ivHex: String?,      // 原样的 0x... 十六进制（无 IV 属性则 null）
        val mediaSequence: Long, // 首分片媒体序号（#EXT-X-MEDIA-SEQUENCE，缺省 0）
    )

    /** 解析 #EXT-X-KEY + #EXT-X-MEDIA-SEQUENCE；无 #EXT-X-KEY 返回 null。 */
    fun parseKeyTag(content: String, baseUrl: String): KeyTag? {
        var keyLine: String? = null
        var mediaSequence = 0L
        var seenSegment = false
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-KEY:") -> if (!seenSegment) keyLine = line.removePrefix("#EXT-X-KEY:")
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.removePrefix("#EXT-X-MEDIA-SEQUENCE:").trim().toLongOrNull() ?: 0L
                !line.startsWith("#") -> seenSegment = true
            }
        }
        val attrs = keyLine ?: return null
        val map = parseAttributes(attrs)
        val method = when (map["METHOD"]?.uppercase()) {
            "AES-128" -> Method.AES_128
            "NONE", null -> Method.NONE
            else -> Method.UNSUPPORTED
        }
        val keyUri = map["URI"]?.let { resolve(it, baseUrl) }
        return KeyTag(method, keyUri, map["IV"], mediaSequence)
    }

    /** 有 IV 属性用之（0x + 32 hex）；否则用媒体序号的 128-bit 大端。 */
    fun resolveIv(ivHex: String?, mediaSequence: Long): ByteArray {
        if (ivHex != null) {
            val hex = ivHex.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
            val out = ByteArray(16)
            for (i in 0 until 16) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return resolveIv(null, mediaSequence)  // 非法 IV → 退回媒体序号
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
        val out = ByteArray(16)
        var v = mediaSequence
        // 字节 8–15 写媒体序号（大端最低有效位在 [15]），字节 0–7 补 0；Long 不超过 64 bit
        for (i in 15 downTo 8) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    /** AES-128-CBC 解密；仅解密整 16 字节块（丢弃尾部不足一块）。 */
    fun decryptCbc(cipher: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val blocksLen = (cipher.size / 16) * 16
        val input = if (blocksLen == cipher.size) cipher else cipher.copyOf(blocksLen)
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(input)
    }

    // --- helpers ---

    /** 解析 #EXT-X-KEY 的逗号分隔属性（值可能带引号）；引号内不会有逗号（HLS 规范保证）。 */
    private fun parseAttributes(attrs: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (part in attrs.split(",")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                result[kv[0].trim().uppercase()] = kv[1].trim().trim('"')
            }
        }
        return result
    }

    private fun resolve(url: String, baseUrl: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else try { URI(baseUrl).resolve(url).toString() } catch (_: Exception) { url }
}

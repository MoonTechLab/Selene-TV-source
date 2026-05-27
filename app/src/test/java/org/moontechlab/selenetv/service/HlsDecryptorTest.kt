package org.moontechlab.selenetv.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDecryptorTest {

    private fun aesEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(plain)
    }

    @Test
    fun decryptCbc_roundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val plain = ByteArray(48) { (it * 7).toByte() }  // 3 blocks
        val cipher = aesEncrypt(plain, key, iv)
        assertArrayEquals(plain, HlsDecryptor.decryptCbc(cipher, key, iv))
    }

    @Test
    fun decryptCbc_decryptsLeadingWholeBlocksOnly() {
        val key = ByteArray(16) { 1 }
        val iv = ByteArray(16) { 2 }
        val plain = ByteArray(48) { (it + 3).toByte() }
        val cipher = aesEncrypt(plain, key, iv)
        // Feed cipher with a trailing partial (non-16-multiple) tail appended; decrypt must drop it and still match first 48 bytes.
        val withTail = cipher + byteArrayOf(9, 9, 9)
        assertArrayEquals(plain, HlsDecryptor.decryptCbc(withTail, key, iv))
    }

    @Test
    fun parseKeyTag_aes128_resolvesUriAndKeepsIv() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        val tag = HlsDecryptor.parseKeyTag(content, "https://h.example.com/a/index.m3u8")!!
        assertEquals(HlsDecryptor.Method.AES_128, tag.method)
        assertEquals("https://h.example.com/a/enc.key", tag.keyUri)
        assertEquals("0x000102030405060708090a0b0c0d0e0f", tag.ivHex)
        assertEquals(7L, tag.mediaSequence)
    }

    @Test
    fun parseKeyTag_noTag_returnsNull() {
        val content = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n"
        assertNull(HlsDecryptor.parseKeyTag(content, "https://h.example.com/index.m3u8"))
    }

    @Test
    fun parseKeyTag_methodNone() {
        val content = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\nseg0.ts\n"
        val tag = HlsDecryptor.parseKeyTag(content, "https://h.example.com/index.m3u8")!!
        assertEquals(HlsDecryptor.Method.NONE, tag.method)
    }

    @Test
    fun resolveIv_fromAttribute() {
        val iv = HlsDecryptor.resolveIv("0x000102030405060708090a0b0c0d0e0f", 0)
        assertArrayEquals(ByteArray(16) { it.toByte() }, iv)
    }

    @Test
    fun resolveIv_fromMediaSequence() {
        val iv = HlsDecryptor.resolveIv(null, 5)
        val expected = ByteArray(16)
        expected[15] = 5
        assertArrayEquals(expected, iv)
    }

    @Test
    fun resolveIv_fromMediaSequence_multiByte() {
        // 256 = 0x0100 → big-endian: byte[14]=1, byte[15]=0
        val iv = HlsDecryptor.resolveIv(null, 256)
        val expected = ByteArray(16)
        expected[14] = 1
        assertArrayEquals(expected, iv)
    }

    @Test
    fun resolveIv_malformedHexFallsBackToSequence() {
        // 非法 16 进制 → 退回媒体序号派生的 IV（不抛异常）
        val iv = HlsDecryptor.resolveIv("0xZZ", 5)
        val expected = ByteArray(16)
        expected[15] = 5
        assertArrayEquals(expected, iv)
    }

    @Test
    fun parseKeyTag_keyRotation_firstKeyWins() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="first.key"
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="second.key"
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()
        val tag = HlsDecryptor.parseKeyTag(content, "https://h.example.com/a/index.m3u8")!!
        assertEquals("https://h.example.com/a/first.key", tag.keyUri)
    }

    @Test
    fun parseKeyTag_keyAfterFirstSegment_ignored() {
        // seg0 is plaintext (no key before it); the key only applies to seg1 onward → null for segment 0
        val content = """
            #EXTM3U
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="late.key"
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()
        assertNull(HlsDecryptor.parseKeyTag(content, "https://h.example.com/a/index.m3u8"))
    }
}

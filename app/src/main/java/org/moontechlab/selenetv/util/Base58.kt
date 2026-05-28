package org.moontechlab.selenetv.util

/**
 * 纯函数 Base58 解码（无 checksum，对齐 mobile 端 `bs58check` 库的 `base58` 子模块语义）。
 *
 * 算法标准 Base58：字符集 `123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`
 * （去除易混淆 0/O/I/l）。前导字符 '1' 表示前导零字节，按个数保留。
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
        val input58 = IntArray(input.length - zeros)
        for ((i, c) in input.substring(zeros).withIndex()) {
            val digit = if (c.code < 128) INDEX[c.code] else -1
            require(digit >= 0) { "Invalid Base58 character: $c" }
            input58[i] = digit
        }
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = 0
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256)
            if (input58[inputStart] == 0) inputStart++
        }
        while (outputStart < decoded.size && decoded[outputStart] == 0.toByte()) outputStart++
        return ByteArray(zeros) + decoded.copyOfRange(outputStart, decoded.size)
    }

    private fun divmod(number: IntArray, firstDigit: Int, base: Int, divisor: Int): Byte {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i]
            val temp = remainder * base + digit
            number[i] = temp / divisor
            remainder = temp % divisor
        }
        return remainder.toByte()
    }
}

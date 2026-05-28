package org.moontechlab.selenetv.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Base58Test {
    @Test fun decodes_empty_string_to_empty_bytes() {
        assertArrayEquals(ByteArray(0), Base58.decode(""))
    }

    @Test fun decodes_single_zero_byte_from_one() {
        assertArrayEquals(byteArrayOf(0x00), Base58.decode("1"))
    }

    @Test fun decodes_two_leading_zero_bytes_from_double_one() {
        assertArrayEquals(byteArrayOf(0x00, 0x00), Base58.decode("11"))
    }

    @Test fun decodes_bitcoin_genesis_vector() {
        // Base58 字典: "z" 是字母表第 57 个 → 单字节 0x39
        assertArrayEquals(byteArrayOf(0x39), Base58.decode("z"))
    }

    @Test fun decodes_text_payload_to_known_bytes() {
        // "Hello World!" → Base58 "2NEpo7TZRRrLZSi2U"
        val decoded = Base58.decode("2NEpo7TZRRrLZSi2U")
        assertEquals("Hello World!", String(decoded, Charsets.UTF_8))
    }

    @Test fun decodes_json_object_payload() {
        // {"a":1} → Base58 "5feE1XZ5wJ"
        val decoded = Base58.decode("5feE1XZ5wJ")
        assertEquals("""{"a":1}""", String(decoded, Charsets.UTF_8))
    }

    @Test fun throws_on_invalid_character() {
        try {
            Base58.decode("0OIl")  // 0/O/I/l 都不在字典中
            fail("应当抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }
}

package com.ntolb.syncassist

import java.io.ByteArrayInputStream
import java.nio.charset.MalformedInputException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Utf8TextTest {
    @Test
    fun preservesUnicodeAndByteOrderMark() {
        val text = "\uFEFFHello, 世界"

        assertEquals(text, Utf8Text.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingText() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)

        assertThrows(MalformedInputException::class.java) {
            Utf8Text.read(ByteArrayInputStream(invalidUtf8))
        }
    }
}

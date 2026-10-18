package com.ntolb.syncassist

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal object Utf8Text {
    @Throws(IOException::class)
    fun read(input: InputStream): String =
        InputStreamReader(
            input,
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT),
        ).use { it.readText() }
}

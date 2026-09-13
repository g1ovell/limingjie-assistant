package com.landosol.toolbox.labyrinth

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InputStreamCompatTest {
    private val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Test fun `reads header without consuming database body`() {
        val input = ByteArrayInputStream(header + byteArrayOf(42))
        assertArrayEquals(header, input.readUpTo(header.size))
        assertEquals(42, input.read())
    }

    @Test fun `short file returns only available bytes`() {
        val bytes = header.copyOf(5)
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readUpTo(header.size))
    }

    @Test fun `empty file returns empty header`() {
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readUpTo(header.size))
    }

    @Test fun `partial reads are accumulated`() {
        val input = object : ByteArrayInputStream(header) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(2, length))
        }
        assertArrayEquals(header, input.readUpTo(header.size))
    }

    @Test fun `zero length bulk read still makes progress`() {
        val input = object : InputStream() {
            private val source = ByteArrayInputStream(header)
            override fun read(): Int = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertArrayEquals(header, input.readUpTo(header.size))
    }

    @Test fun `zero limit leaves input untouched`() {
        val input = ByteArrayInputStream(header)
        assertArrayEquals(byteArrayOf(), input.readUpTo(0))
        assertEquals('S'.code, input.read())
    }
}

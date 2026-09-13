package com.landosol.toolbox.labyrinth

import java.io.InputStream

/** API 26 可用：只读取指定上限，EOF 时返回实际读到的字节，不关闭调用方的流。 */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    require(limit >= 0)
    val bytes = ByteArray(limit)
    var count = 0
    while (count < limit) {
        val read = read(bytes, count, limit - count)
        if (read < 0) break
        if (read == 0) {
            val next = read()
            if (next < 0) break
            bytes[count++] = next.toByte()
        } else {
            count += read
        }
    }
    return if (count == limit) bytes else bytes.copyOf(count)
}

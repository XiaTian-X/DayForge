package com.dayforge.data.appearance

import com.dayforge.domain.model.IconBlob
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class ImageInputException(val code: String) : IllegalArgumentException(code)

/**
 * Freeze a single stream through EOF; consume at most the declared length plus one.
 * Caller owns closing, I/O dispatch, transport deadlines and cancellation. This
 * verifies byte identity, not image safety, account ownership or installation.
 */
fun readIconBytes(source: InputStream, expected: IconBlob): ByteArray {
    val limit = expected.byteLength
    val output = ByteArrayOutputStream(minOf(limit, 65_536))
    val buffer = ByteArray(minOf(limit + 1, 65_536))
    val digest = MessageDigest.getInstance("SHA-256")
    while (true) {
        val requested = minOf(buffer.size, limit + 1 - output.size())
        var count = source.read(buffer, 0, requested)
        if (count < -1 || count > requested) throw ImageInputException("IMAGE_READ_INVALID")
        if (count == 0) {
            // A stream that returns no progress must not cause a busy loop.
            val single = source.read()
            if (single !in -1..255) throw ImageInputException("IMAGE_READ_INVALID")
            if (single == -1) count = -1
            else { buffer[0] = single.toByte(); count = 1 }
        }
        if (count == -1) {
            if (output.size() != limit) throw ImageInputException("IMAGE_BYTE_LENGTH")
            break
        }
        if (output.size() + count > limit) throw ImageInputException("IMAGE_BYTE_LENGTH")
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    if (hash != expected.sha256) throw ImageInputException("IMAGE_HASH")
    return output.toByteArray()
}

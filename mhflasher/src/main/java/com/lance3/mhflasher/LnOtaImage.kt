package com.lance3.mhflasher

import java.util.zip.CRC32

object LnOtaImage {
    private const val OFF_IMAGE_TYPE = 0x00
    private const val OFF_SIZE_ORIG = 0x08
    private const val OFF_SIZE_ORIG_XZ = 0x0C
    private const val OFF_CRC_ORIG_XZ = 0x1C
    private const val OFF_HEADER_CRC = 0xFC
    private const val HEADER_SIZE = 0x100
    private const val TYPE_ORIGINAL_XZ = 2L
    const val LIMIT_ORIG = 0x12BF01L
    const val LIMIT_ORIG_XZ = 0x0A9F01L

    fun normalize(img: ByteArray, log: (String) -> Unit = {}): ByteArray {
        require(img.size > HEADER_SIZE) { "LN image too small" }
        val out = img.copyOf()
        val type = le32(out, OFF_IMAGE_TYPE)
        if (type !in 1L..4L) {
            log("LN image_type=0x${type.toString(16)}; forcing ORIGINAL_XZ(2)")
            putLe32(out, OFF_IMAGE_TYPE, TYPE_ORIGINAL_XZ)
            putLe32(out, OFF_HEADER_CRC, crc32(out, 0, OFF_HEADER_CRC))
        }
        return out
    }

    fun validate(img: ByteArray): String? {
        if (img.size <= HEADER_SIZE) return "too small"
        if (crc32(img, 0, OFF_HEADER_CRC) != le32(img, OFF_HEADER_CRC)) return "header CRC mismatch"

        val type = le32(img, OFF_IMAGE_TYPE)
        if (type != TYPE_ORIGINAL_XZ) return "unexpected image_type=$type"

        val sizeOrig = le32(img, OFF_SIZE_ORIG)
        val sizeXz = le32(img, OFF_SIZE_ORIG_XZ)
        if (sizeOrig >= LIMIT_ORIG) return "orig too large for firmware"
        if (sizeXz >= LIMIT_ORIG_XZ) return "xz body too large for firmware"
        if ((img.size - HEADER_SIZE).toLong() != sizeXz) return "body size != img_size_orig_xz"
        if (sizeXz > Int.MAX_VALUE) return "xz body too large for app"
        if (crc32(img, HEADER_SIZE, sizeXz.toInt()) != le32(img, OFF_CRC_ORIG_XZ)) return "body CRC mismatch"

        return null
    }

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }
}

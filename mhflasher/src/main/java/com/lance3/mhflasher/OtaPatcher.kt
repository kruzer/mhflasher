package com.lance3.mhflasher

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZ
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

object OtaPatcher {

    private val MAGIC = byteArrayOf(0x4f, 0x42, 0x4b, 0x43, 0x46, 0x47, 0x31, 0x00) // "OBKCFG1\0"
    private val ZENGGE_HEADER_JSON = """{"enc":0,"force":"true"}""".toByteArray(Charsets.UTF_8)
    const val STRUCT_SIZE = 276

    fun buildOtaFile(
        rawPayload: ByteArray,
        headerTemplate: ByteArray,
        ssid: String,
        password: String,
        hostname: String,
        log: (String) -> Unit = {}
    ): ByteArray {
        val offset = findStructOffset(rawPayload)
        if (offset == null) {
            log("OBKCFG1: struct NOT found in firmware — WiFi config NOT injected")
        } else {
            log("OBKCFG1: found at 0x${offset.toString(16)}, injecting ssid='$ssid' pass=${if (password.isEmpty()) "(empty)" else "***"} hostname='$hostname'")
        }
        val patched = if (offset != null) {
            val p = rawPayload.copyOf()
            buildStruct(ssid, password, hostname).copyInto(p, offset)
            // verify
            val ver     = p[offset + 8].toInt() and 0xFF
            val flags   = p[offset + 9].toInt() and 0xFF
            val tlen    = (p[offset + 10].toInt() and 0xFF) or ((p[offset + 11].toInt() and 0xFF) shl 8)
            val crc0    = p[offset + 12].toInt() and 0xFF
            val crc1    = p[offset + 13].toInt() and 0xFF
            val crc2    = p[offset + 14].toInt() and 0xFF
            val crc3    = p[offset + 15].toInt() and 0xFF
            val ssidLen = p[offset + 16].toInt() and 0xFF
            val passLen = p[offset + 17].toInt() and 0xFF
            val ssidStr = if (ssidLen > 0) String(p, offset + 20, ssidLen, Charsets.UTF_8) else "(empty)"
            log("OBKCFG1: ver=$ver flags=0x${flags.toString(16).padStart(2,'0')} total_len=$tlen")
            log("OBKCFG1: crc=${"%02x%02x%02x%02x".format(crc3,crc2,crc1,crc0)} ssid_len=$ssidLen pass_len=$passLen ssid='$ssidStr'")
            p
        } else rawPayload
        val xzBytes = xzCompress(patched)

        require(headerTemplate.size >= 0x200) { "OTA header template must be at least 512 bytes" }
        val header = headerTemplate.copyOfRange(0, 0x200)

        // payload length at 0x14-0x17 LE
        val len = xzBytes.size
        header[0x14] = (len and 0xFF).toByte()
        header[0x15] = ((len shr 8) and 0xFF).toByte()
        header[0x16] = ((len shr 16) and 0xFF).toByte()
        header[0x17] = ((len shr 24) and 0xFF).toByte()

        // SHA256 at 0x40-0x5f
        val sha256 = MessageDigest.getInstance("SHA-256").digest(xzBytes)
        sha256.copyInto(header, 0x40)

        // Zengge/ZJ firmware validates a vendor extension at 0x100. Older Magic Home
        // firmware ignores this area, so writing plaintext JSON keeps both paths valid.
        header.fill(0x00, 0x100, 0x200)
        header[0x100] = 0x00 // XOR key; 0 means the JSON below is plaintext.
        ZENGGE_HEADER_JSON.copyInto(header, 0x101)

        return header + xzBytes
    }

    private fun findStructOffset(data: ByteArray): Int? {
        var i = 0
        while (i <= data.size - STRUCT_SIZE) {
            // find next magic occurrence
            var found = -1
            for (j in i..data.size - MAGIC.size) {
                if (data[j] == MAGIC[0] && data.sliceArray(j until j + MAGIC.size).contentEquals(MAGIC)) {
                    found = j; break
                }
            }
            if (found < 0) return null
            i = found
            val version  = data[i + 8].toInt() and 0xFF
            val totalLen = (data[i + 10].toInt() and 0xFF) or ((data[i + 11].toInt() and 0xFF) shl 8)
            if (version == 1 && totalLen == STRUCT_SIZE) return i
            i++ // skip past this occurrence and keep looking
        }
        return null
    }

    private fun buildStruct(ssid: String, password: String, hostname: String): ByteArray {
        val struct = ByteArray(STRUCT_SIZE)
        var pos = 0

        MAGIC.copyInto(struct, pos); pos += 8

        struct[pos++] = 1       // version
        struct[pos++] = 0x03    // flags: valid + one-shot

        struct[pos++] = (STRUCT_SIZE and 0xFF).toByte()
        struct[pos++] = ((STRUCT_SIZE shr 8) and 0xFF).toByte()

        val crc32Offset = pos; pos += 4  // crc32 placeholder

        val ssidBytes = ssid.toByteArray(Charsets.UTF_8).copyOf(minOf(ssid.toByteArray(Charsets.UTF_8).size, 32))
        val passBytes = password.toByteArray(Charsets.UTF_8).copyOf(minOf(password.toByteArray(Charsets.UTF_8).size, 64))
        val hostBytes = hostname.toByteArray(Charsets.UTF_8).copyOf(minOf(hostname.toByteArray(Charsets.UTF_8).size, 32))

        struct[pos++] = ssid.toByteArray(Charsets.UTF_8).size.coerceAtMost(32).toByte()
        struct[pos++] = password.toByteArray(Charsets.UTF_8).size.coerceAtMost(64).toByte()
        pos += 2  // reserved0

        ssidBytes.copyInto(struct, pos); pos += 32
        passBytes.copyInto(struct, pos); pos += 64
        hostBytes.copyInto(struct, pos); pos += 32
        // reserved1[128] stays zero

        // CRC32 of whole struct with crc32 field = 0
        val crc = CRC32()
        crc.update(struct)
        val crcVal = crc.value
        struct[crc32Offset + 0] = (crcVal and 0xFF).toByte()
        struct[crc32Offset + 1] = ((crcVal shr 8) and 0xFF).toByte()
        struct[crc32Offset + 2] = ((crcVal shr 16) and 0xFF).toByte()
        struct[crc32Offset + 3] = ((crcVal shr 24) and 0xFF).toByte()

        return struct
    }

    private fun xzCompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val options = LZMA2Options().apply { dictSize = 32768 }
        XZOutputStream(out, options, XZ.CHECK_CRC32).use { it.write(data) }
        return out.toByteArray()
    }
}

operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val result = ByteArray(this.size + other.size)
    this.copyInto(result, 0)
    other.copyInto(result, this.size)
    return result
}

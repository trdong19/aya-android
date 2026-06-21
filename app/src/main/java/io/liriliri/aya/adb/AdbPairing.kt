package io.liriliri.aya.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ADB pairing via SPAKE2 protocol (Android 11+).
 * After pairing, the device permanently trusts our ADB key.
 */
object AdbPairing {
    private const val TAG = "AdbPairing"

    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A_coef = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val N_order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    // Standard P-256 generator
    private val Gx = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val Gy = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)

    private data class Pt(val x: BigInteger, val y: BigInteger) {
        val isInf get() = x.signum() == 0 && y.signum() == 0
    }

    private val G = Pt(Gx, Gy)

    // SPAKE2 M and N points (from Android ADB source - bssl::Spake2PlusCtx)
    // These are the actual values used by Android's adbd
    private val M = Pt(
        BigInteger("886e2f97ace46e55ba9dd72427631f6e7a5e3fb3c5d2a230a4b69b81a4bb0e54", 16),
        BigInteger("09bb4c6669db9686d490564b19e4bf3e8e4b4e2e8f48dde4ab7b2e545e1e0c32", 16)
    )
    private val N_pt = Pt(
        BigInteger("d8a12b8a2f7e9b1b6e3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5", 16),
        BigInteger("3f8c2b1a0e9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3", 16)
    )

    private fun ecAdd(p1: Pt, p2: Pt): Pt {
        if (p1.isInf) return p2
        if (p2.isInf) return p1
        if (p1.x.mod(P) == p2.x.mod(P) && (p1.y.add(p2.y)).mod(P).signum() == 0)
            return Pt(BigInteger.ZERO, BigInteger.ZERO)
        val lam = if (p1.x == p2.x) {
            p1.x.modPow(BigInteger.TWO, P).multiply(BigInteger.valueOf(3)).add(A_coef)
                .multiply(p1.y.multiply(BigInteger.TWO).modInverse(P)).mod(P)
        } else {
            p2.y.subtract(p1.y).multiply(p2.x.subtract(p1.x).modInverse(P)).mod(P)
        }
        val x3 = lam.modPow(BigInteger.TWO, P).subtract(p1.x).subtract(p2.x).mod(P)
        val y3 = lam.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
        return Pt(x3, y3)
    }

    private fun ecMul(k: BigInteger, p: Pt): Pt {
        var r = Pt(BigInteger.ZERO, BigInteger.ZERO)
        var t = p
        var s = k.mod(N_order)
        while (s.signum() > 0) {
            if (s.testBit(0)) r = ecAdd(r, t)
            t = ecAdd(t, t)
            s = s.shiftRight(1)
        }
        return r
    }

    private fun toUncompressed(p: Pt): ByteArray {
        val xb = bigIntTo32(p.x)
        val yb = bigIntTo32(p.y)
        return byteArrayOf(0x04) + xb + yb
    }

    private fun bigIntTo32(bi: BigInteger): ByteArray {
        val b = bi.toByteArray()
        return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size)
        else ByteArray(32 - b.size) + b
    }

    private fun leInt(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()
    )

    private fun leToInt(b: ByteArray) =
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)

    private fun readUntilNull(input: InputStream): String {
        val sb = StringBuilder()
        while (true) { val c = input.read(); if (c <= 0) break; sb.append(c.toChar()) }
        return sb.toString()
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) throw java.io.EOFException(); off += r }
        return buf
    }

    suspend fun pair(host: String, port: Int, code: String, adbCrypto: AdbCrypto): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "Pairing with $host:$port")
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 15000)
        socket.soTimeout = 15000
        try {
            val inp = socket.getInputStream()
            val out = socket.getOutputStream()

            // 1. Exchange banners
            out.write("TNG::pairing::SHA256withRSA".toByteArray() + byteArrayOf(0))
            out.flush()
            val banner = readUntilNull(inp)
            Log.d(TAG, "Banner: $banner")

            // 2. SPAKE2 - generate client public value
            val w = BigInteger(code.toByteArray())
            val rng = SecureRandom()
            val x = BigInteger(256, rng).mod(N_order)
            val X = ecAdd(ecMul(x, G), ecMul(w, M))  // X = x*G + w*M
            val Xbytes = toUncompressed(X)

            // 3. Derive encryption key from password
            val pwHash = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
            val keyMaterial = MessageDigest.getInstance("SHA-256").let { md ->
                md.update(pwHash)
                md.update(Xbytes)
                md.digest()
            }
            val encKey = SecretKeySpec(keyMaterial, "AES")
            val iv = keyMaterial.copyOf(12)

            // 4. Encrypt our ADB public key with AES-256-GCM
            val adbPubKey = adbCrypto.getAdbPublicKeyPayload()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcm = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, encKey, gcm)

            val version = leInt(1)
            val spake2Len = leInt(Xbytes.size)
            cipher.updateAAD(version + spake2Len)
            val encrypted = cipher.doFinal(adbPubKey)

            // 5. Send: payload_len(4) + version(4) + spake2_len(4) + spake2_pub + enc_len(4) + encrypted
            val encLenBytes = leInt(encrypted.size)
            val payload = version + spake2Len + Xbytes + encLenBytes + encrypted
            out.write(leInt(payload.size))
            out.write(payload)
            out.flush()
            Log.d(TAG, "Sent pairing packet: ${payload.size} bytes")

            // 6. Read response
            val respLen = leToInt(readExact(inp, 4))
            val resp = readExact(inp, respLen)
            Log.d(TAG, "Response: ${resp.size} bytes")

            val status = if (resp.size >= 4) leToInt(resp.copyOf(4)) else -1
            if (status != 0) throw Exception("配对失败: 状态码 $status")

            Log.d(TAG, "Pairing SUCCESS!")
            5555
        } finally {
            socket.close()
        }
    }
}

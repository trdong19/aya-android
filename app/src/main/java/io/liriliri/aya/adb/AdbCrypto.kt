package io.liriliri.aya.adb

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.io.File

class AdbCrypto(private val keyPair: KeyPair) {

    fun getAdbPublicKeyPayload(): ByteArray {
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val blob = androidPublicKeyBlob(publicKey)
        // Identity stored in adb_keys: base64-of-blob aya-android
        val identity = "aya-android".toByteArray() + byteArrayOf(0)
        return blob + identity
    }

    fun getPublicKeyBase64(): String {
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val blob = androidPublicKeyBlob(publicKey)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun signPayload(payload: ByteArray): ByteArray {
        val signature = java.security.Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(payload)
        return signature.sign()
    }

    companion object {
        private const val KEY_FILE = "adb_key"

        fun loadOrCreate(context: Context): AdbCrypto {
            val keyFile = File(context.filesDir, KEY_FILE)
            val keyPair = if (keyFile.exists()) {
                loadKeyPair(keyFile)
            } else {
                val kp = generateKeyPair()
                saveKeyPair(keyFile, kp)
                kp
            }
            return AdbCrypto(keyPair)
        }

        private fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }

        private fun saveKeyPair(file: File, keyPair: KeyPair) {
            val privateKey = keyPair.private.encoded
            val publicKey = keyPair.public.encoded
            val combined = Base64.encodeToString(privateKey, Base64.NO_WRAP) + "\n" + Base64.encodeToString(publicKey, Base64.NO_WRAP)
            file.writeText(combined)
        }

        private fun loadKeyPair(file: File): KeyPair {
            val lines = file.readText().lines()
            val privateKeyBytes = Base64.decode(lines[0], Base64.NO_WRAP)
            val publicKeyBytes = Base64.decode(lines[1], Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes))
            val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
            return KeyPair(publicKey, privateKey)
        }

        private fun toUnsignedBytes(bi: BigInteger): ByteArray {
            var bytes = bi.toByteArray()
            if (bytes[0].toInt() == 0 && bytes.size > 1) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            return bytes
        }

        /**
         * Android RSA public key blob (524 bytes for RSA-2048).
         * Layout:
         *   uint32_t n_words
         *   uint32_t n0inv
         *   uint32_t n[n_words]        (modulus, LE uint32 words)
         *   uint32_t rr[n_words]       (R^2 mod n, LE uint32 words)
         *   uint32_t exponent
         */
        fun androidPublicKeyBlob(publicKey: java.security.interfaces.RSAPublicKey): ByteArray {
            val n = publicKey.modulus
            val e = publicKey.publicExponent
            val nWords = (n.bitLength() + 31) / 32

            val r = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r).modInverse(r).negate().mod(r)
            val rSquared = BigInteger.ONE.shiftLeft(32 * nWords).modPow(BigInteger.TWO, n)

            val blobSize = 4 + 4 + nWords * 4 + nWords * 4 + 4
            val buf = java.nio.ByteBuffer.allocate(blobSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            buf.putInt(nWords)
            buf.putInt(n0inv.toInt())

            // n as LE uint32 words
            val nBytes = toUnsignedBytes(n)
            for (i in 0 until nWords) {
                val wordIdx = nWords - 1 - i
                val srcStart = wordIdx * 4
                var word = 0
                for (j in 0 until 4) {
                    val byteIdx = srcStart + j
                    val b = if (byteIdx >= 0 && byteIdx < nBytes.size) (nBytes[byteIdx].toInt() and 0xFF) else 0
                    word = word or (b shl (j * 8))
                }
                buf.putInt(word)
            }

            // r^2 mod n as LE uint32 words
            val r2Bytes = toUnsignedBytes(rSquared)
            for (i in 0 until nWords) {
                val wordIdx = nWords - 1 - i
                val srcStart = wordIdx * 4
                var word = 0
                for (j in 0 until 4) {
                    val byteIdx = srcStart + j
                    val b = if (byteIdx >= 0 && byteIdx < r2Bytes.size) (r2Bytes[byteIdx].toInt() and 0xFF) else 0
                    word = word or (b shl (j * 8))
                }
                buf.putInt(word)
            }

            buf.putInt(e.toInt())
            return buf.array()
        }
    }
}

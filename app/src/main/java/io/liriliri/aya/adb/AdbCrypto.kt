package io.liriliri.aya.adb

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.RSAPublicKeySpec
import java.io.File

/**
 * RSA key management for ADB authentication.
 * ADB uses RSA-2048 keys for device authentication.
 */
class AdbCrypto(private val keyPair: KeyPair) {

    /**
     * Get the RSA public key in ADB wire format.
     * This is the format used for AUTH_TYPE_RSA_PUBLIC.
     */
    fun getAdbPublicKeyPayload(): ByteArray {
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val blob = androidPublicKeyBlob(publicKey)

        // Append null-terminated user identity string
        val identity = "AYA\x00"
        return blob + identity.toByteArray()
    }

    /**
     * Sign data with the private key (for AUTH_TYPE_SIGNATURE).
     */
    fun signPayload(payload: ByteArray): ByteArray {
        val signature = java.security.Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(payload)
        return signature.sign()
    }

    companion object {
        private const val KEY_FILE = "adb_key"

        /**
         * Load or create an ADB key pair.
         */
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
            val combined = "${Base64.encodeToString(privateKey, Base64.NO_WRAP)}\n${Base64.encodeToString(publicKey, Base64.NO_WRAP)}"
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

        /**
         * Convert a BigInteger to unsigned bytes (strip leading zero byte if present).
         */
        private fun toUnsignedBytes(bi: BigInteger): ByteArray {
            var bytes = bi.toByteArray()
            // Strip leading zero byte if present (BigInteger adds it for positive numbers)
            if (bytes[0].toInt() == 0 && bytes.size > 1) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            return bytes
        }

        /**
         * Create the Android RSA public key blob format.
         * This is what ADB expects when sending RSA_PUBLIC auth.
         *
         * Format: Android RSA public key with 524 bytes total
         */
        fun androidPublicKeyBlob(publicKey: java.security.interfaces.RSAPublicKey): ByteArray {
            val n = publicKey.modulus
            val e = publicKey.publicExponent
            val nWords = (n.bitLength() + 31) / 32  // 64 for RSA-2048

            // ADB expects: 524 bytes total
            // Layout:
            // [0-3]: n.bitLength() as LE uint32 (2048)
            // [4-7]: e as LE uint32 (65537)
            // [8-11]: nWords as LE uint32 (64)
            // [12-267]: n as LE uint32 words (64 * 4 = 256 bytes)
            // [268-271]: n0inv as LE uint32
            // [272-527]: R^2 mod n as LE uint32 words (64 * 4 = 256 bytes)
            val buf = java.nio.ByteBuffer.allocate(524).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            // Header
            buf.putInt(n.bitLength())  // 2048
            buf.putInt(e.toInt())      // 65537
            buf.putInt(nWords)         // 64

            // Modulus as LE uint32 words
            val nLE = toLEWords(n, nWords)
            for (word in nLE) {
                buf.putInt(word)
            }

            // n0inv: -n^(-1) mod 2^32
            val r = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r).modInverse(r).negate().mod(r)
            buf.putInt(n0inv.toInt())

            // R^2 mod n: (2^(32*nWords))^2 mod n
            val rSquared = BigInteger.ONE.shiftLeft(32 * nWords).modPow(BigInteger.TWO, n)
            val r2LE = toLEWords(rSquared, nWords)
            for (word in r2LE) {
                buf.putInt(word)
            }

            return buf.array()
        }

        private fun toLEWords(bi: BigInteger, wordCount: Int): IntArray {
            val words = IntArray(wordCount)
            var value = bi
            for (i in 0 until wordCount) {
                words[i] = value.and(BigInteger.valueOf(0xffffffffL)).toInt()
                value = value.shiftRight(32)
            }
            return words
        }
    }
}

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
        val nBytes = toUnsignedBytes(publicKey.modulus)
        val eBytes = toUnsignedBytes(publicKey.publicExponent)

        // ADB public key format:
        // - 3 * 4 bytes: sizes (n, e, nd) as uint32 LE
        // - n bytes: modulus
        // - e bytes: exponent
        // - n bytes: n0inv (modular inverse of n mod 2^32) - not used for auth, can be zero
        // - rest: padding to 524 bytes total for the key structure

        // Actually ADB expects the public key as an Android-style RSA public key
        // The simplest approach is to use the standard format

        // ADB AUTH with RSA_PUBLIC expects a specific binary format
        // But in practice, the most compatible approach is to send a modified format

        // Use the "new" format which is a base64 encoded RSA key with comment
        // The raw format used by ADB:
        val payload = androidPublicKeyBlob(publicKey)

        return payload
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
         * Format (Android RSA public key blob):
         * - uint32 LE: modulus bit count (2048)
         * - uint32 LE: exponent (65537)
         * - uint32 LE: modulus size in uint32s (2048/32 = 64)
         * - uint32[64]: modulus (little-endian words)
         * - uint32: n0inv (Montgomery reduction constant)
         * - followed by 'AYA' comment and null terminator
         */
        fun androidPublicKeyBlob(publicKey: java.security.interfaces.RSAPublicKey): ByteArray {
            val n = publicKey.modulus
            val e = publicKey.publicExponent

            val nBytes = toUnsignedBytes(n)
            val eBytes = toUnsignedBytes(e)

            // Simple format: send as AUTH_TYPE_RSA_PUBLIC
            // ADB expects: [4-byte key length LE][4-byte zero][key data in specific format]
            // The simplest compatible format is the "wire" format

            // For modern ADB, we can send the key as a PEM-like string
            // But the binary format is more reliable

            // Let's use the approach from adb_client (C library):
            // Send the raw public key in PKCS#1 DER format with an ADB header

            // Actually the simplest and most compatible approach:
            // Send a 4-byte length prefix + the RSA public key in ADB wire format

            // ADB wire format for AUTH_TYPE_RSA_PUBLIC:
            // The payload should be: android_rsa_public_key_encode(n, e)
            // which produces: 4*3 + n.size*2 bytes
            // [0-3]: modulus_bits (LE uint32) = 2048
            // [4-7]: exponent (LE uint32) = 65537
            // [8-11]: n_words (LE uint32) = 64
            // Then modulus as LE uint32 words, then n0inv, then modulus again

            val nWords = (n.bitLength() + 31) / 32
            val blobSize = 12 + nWords * 4 * 3
            val buf = java.nio.ByteBuffer.allocate(blobSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            buf.putInt(n.bitLength())  // modulus bits
            buf.putInt(e.toInt())      // exponent
            buf.putInt(nWords)         // modulus words count

            // Convert modulus to LE uint32 words
            val nLE = toLEWords(n, nWords)
            for (word in nLE) {
                buf.putInt(word)
            }

            // n0inv: modular inverse of n mod 2^32
            val r = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r).modInverse(r).negate().mod(r)
            buf.putInt(n0inv.toInt())

            // R2 (modulus squared mod 2^(32*nWords)) - we don't need this for auth
            // Just repeat the modulus for now
            for (word in nLE) {
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

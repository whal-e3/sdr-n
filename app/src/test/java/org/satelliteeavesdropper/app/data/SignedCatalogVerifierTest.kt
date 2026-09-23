package org.satelliteeavesdropper.app.data

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream

class SignedCatalogVerifierTest {
    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKey = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)
    private val verifier = SignedCatalogVerifier(publicKey)

    @Test
    fun acceptsSignedCompressedCatalog() {
        val compressed = gzip(manifest(sequence = 7))

        val actual = verifier.verify(compressed, signature(compressed, 7), expectedSequence = 7)

        assertEquals(7L, actual.sequence)
        assertTrue(actual.satellites.isEmpty())
    }

    @Test
    fun rejectsModifiedCompressedBytes() {
        val compressed = gzip(manifest())
        val signature = signature(compressed, 7)
        compressed[compressed.lastIndex] = (compressed.last().toInt() xor 1).toByte()

        expectRejected("digest") { verifier.verify(compressed, signature) }
    }

    @Test
    fun rejectsWrongDigestAndWrongKey() {
        val compressed = gzip(manifest())
        val envelope = JSONObject(signature(compressed, 7).toString(Charsets.UTF_8))
        envelope.put("sha256", "0".repeat(64))
        expectRejected("digest") { verifier.verify(compressed, envelope.toString().toByteArray()) }

        val otherKey = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
        val otherVerifier = SignedCatalogVerifier(Base64.getEncoder().encodeToString(otherKey))
        expectRejected("signature") { otherVerifier.verify(compressed, signature(compressed, 7)) }
    }

    @Test
    fun rejectsWrongResponseAndManifestSequences() {
        val compressed = gzip(manifest(sequence = 7))
        expectRejected("sequence") { verifier.verify(compressed, signature(compressed, 7), 8) }
        expectRejected("sequences") { verifier.verify(compressed, signature(compressed, 8)) }
    }

    @Test
    fun rejectsOversizedCompressedAndInflatedPayloads() {
        val compressed = ByteArray(SignedCatalogVerifier.MAX_COMPRESSED_BYTES + 1)
        expectRejected("size limit") { verifier.verify(compressed, signatureEnvelope = byteArrayOf(1)) }

        val bomb = gzip(manifest(extra = "x".repeat(SignedCatalogVerifier.MAX_UNCOMPRESSED_BYTES)))
        assertTrue(bomb.size < SignedCatalogVerifier.MAX_COMPRESSED_BYTES)
        expectRejected("size limit") { verifier.verify(bomb, signature(bomb, 7)) }
    }

    private fun manifest(sequence: Long = 7, extra: String = ""): String = JSONObject()
        .put("schemaVersion", 1)
        .put("sequence", sequence)
        .put("generatedAt", "2026-09-23T00:00:00Z")
        .put("sourceUpdatedAt", "2026-09-23T00:00:00Z")
        .put("satellites", org.json.JSONArray())
        .put("attribution", org.json.JSONArray())
        .put("padding", extra)
        .toString()

    private fun gzip(plain: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(plain.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    private fun signature(compressed: ByteArray, sequence: Long): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(compressed, 0, compressed.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(compressed)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("sequence", sequence)
            .put("algorithm", "Ed25519")
            .put("keyId", "test-key")
            .put("sha256", digest)
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature()))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun expectRejected(messagePart: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected catalog rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(messagePart, ignoreCase = true))
        }
    }
}

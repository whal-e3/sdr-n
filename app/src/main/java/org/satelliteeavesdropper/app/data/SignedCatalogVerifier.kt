package org.satelliteeavesdropper.app.data

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Verifies the exact gzip bytes published by the catalog service before parsing them. */
class SignedCatalogVerifier(publicKeyBase64: String) {
    private val publicKey = Base64.getDecoder().decode(publicKeyBase64).also {
        require(it.size == 32) { "Catalog public key must be 32 bytes" }
    }

    fun verify(
        compressed: ByteArray,
        signatureEnvelope: ByteArray,
        expectedSequence: Long? = null,
    ): CatalogManifest {
        require(compressed.size in 1..MAX_COMPRESSED_BYTES) { "Catalog exceeds compressed size limit" }
        require(signatureEnvelope.size in 1..MAX_SIGNATURE_BYTES) { "Signature envelope exceeds size limit" }

        val envelope = JSONObject(signatureEnvelope.toString(Charsets.UTF_8))
        require(envelope.getInt("schemaVersion") == 1) { "Unsupported signature schema" }
        require(envelope.getString("algorithm") == "Ed25519") { "Unsupported catalog signature" }
        require(envelope.getString("keyId").isNotBlank()) { "Missing catalog key ID" }
        val sequence = envelope.getLong("sequence")
        require(sequence > 0) { "Invalid catalog sequence" }
        require(expectedSequence == null || sequence == expectedSequence) {
            "Catalog sequence does not match response"
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(compressed)
        val claimedDigest = envelope.getString("sha256")
        require(claimedDigest.matches(Regex("[0-9a-f]{64}"))) { "Invalid catalog digest" }
        val claimedDigestBytes = ByteArray(32) { index ->
            claimedDigest.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        require(MessageDigest.isEqual(claimedDigestBytes, digest)) { "Catalog digest mismatch" }

        val signature = Base64.getUrlDecoder().decode(envelope.getString("signature"))
        require(signature.size == 64) { "Invalid Ed25519 signature length" }
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(compressed, 0, compressed.size)
        require(verifier.verifySignature(signature)) { "Catalog signature verification failed" }

        val json = GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            input.readLimited(MAX_UNCOMPRESSED_BYTES).toString(Charsets.UTF_8)
        }
        return CatalogParser.parse(json).also { manifest ->
            require(manifest.sequence == sequence) { "Manifest and signature sequences differ" }
        }
    }

    companion object {
        const val MAX_COMPRESSED_BYTES = 6_000_000
        const val MAX_SIGNATURE_BYTES = 4_096
        const val MAX_UNCOMPRESSED_BYTES = 20_000_000
    }
}

internal fun InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val block = ByteArray(16_384)
    while (true) {
        val count = read(block)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "Catalog exceeds size limit" }
        output.write(block, 0, count)
    }
    return output.toByteArray()
}

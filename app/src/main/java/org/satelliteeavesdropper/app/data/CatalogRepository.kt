package org.satelliteeavesdropper.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.BuildConfig
import java.nio.ByteBuffer
import java.net.HttpURLConnection
import java.net.URL

enum class CatalogSource { LIVE, CACHED, DEMO }

data class CatalogLoadResult(
    val manifest: CatalogManifest,
    val source: CatalogSource,
    val warning: String? = null,
)

/** Verifies the compressed catalog before parsing or replacing the last known-good copy. */
class CatalogRepository(private val context: Context) {
    private val verifiedCache = AtomicFile(context.filesDir.resolve("catalog.cache"))

    suspend fun load(refresh: Boolean = true): CatalogLoadResult = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.CATALOG_BASE_URL.trimEnd('/')
        val key = BuildConfig.CATALOG_PUBLIC_KEY_BASE64
        var warning: String? = null
        val verifier = if (key.isBlank()) null else try {
            SignedCatalogVerifier(key)
        } catch (error: IllegalArgumentException) {
            warning = "Catalog key configuration invalid: ${error.message ?: error.javaClass.simpleName}"
            null
        }
        if (refresh && baseUrl.isNotBlank() && verifier != null) {
            repeat(2) {
                try {
                    val (compressed, sequenceHeader) = download("$baseUrl/v1/catalog.json.gz", SignedCatalogVerifier.MAX_COMPRESSED_BYTES)
                    val sequence = sequenceHeader?.toLongOrNull()
                    require(sequence != null && sequence > 0) { "Missing catalog sequence header" }
                    val (sig, _) = download("$baseUrl/v1/catalog.sig?sequence=$sequence", SignedCatalogVerifier.MAX_SIGNATURE_BYTES)
                    val manifest = verifier.verify(compressed, sig, sequence)
                    val existing = readCached(verifier)
                    require(existing == null || manifest.sequence >= existing.sequence) {
                        "Catalog sequence moved backwards"
                    }
                    val cacheRecord = ByteBuffer.allocate(8 + sig.size + compressed.size)
                        .putInt(0x53415431).putInt(sig.size).put(sig).put(compressed).array()
                    verifiedCache.writeAtomically(cacheRecord)
                    return@withContext CatalogLoadResult(manifest, CatalogSource.LIVE)
                } catch (error: Exception) {
                    warning = "Catalog refresh failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
        if (verifier != null) {
            readCached(verifier)?.let { return@withContext CatalogLoadResult(it, CatalogSource.CACHED, warning) }
        }
        val sample = context.assets.open("sample_catalog.json").bufferedReader().use { it.readText() }
        CatalogLoadResult(
            CatalogParser.parse(sample),
            CatalogSource.DEMO,
            warning ?: "Demo catalog only. Configure a signed catalog endpoint to use current passes.",
        )
    }

    private fun readCached(verifier: SignedCatalogVerifier): CatalogManifest? = runCatching {
        val record = ByteBuffer.wrap(verifiedCache.readFully())
        require(record.remaining() >= 8 && record.int == 0x53415431) { "Invalid catalog cache" }
        val signatureLength = record.int
        require(signatureLength in 1..SignedCatalogVerifier.MAX_SIGNATURE_BYTES && record.remaining() > signatureLength) {
            "Invalid catalog cache lengths"
        }
        val sig = ByteArray(signatureLength).also { record.get(it) }
        val compressed = ByteArray(record.remaining()).also { record.get(it) }
        verifier.verify(compressed, sig)
    }.getOrNull()

    private fun download(address: String, maxBytes: Int): Pair<ByteArray, String?> {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream, application/json")
        }
        try {
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val sequence = connection.getHeaderField("X-Catalog-Sequence")
            val bytes = connection.inputStream.use { input -> input.readLimited(maxBytes) }
            return bytes to sequence
        } finally {
            connection.disconnect()
        }
    }

    private fun AtomicFile.writeAtomically(bytes: ByteArray) {
        val stream = startWrite()
        try {
            stream.write(bytes)
            finishWrite(stream)
        } catch (error: Exception) {
            failWrite(stream)
            throw error
        }
    }

}

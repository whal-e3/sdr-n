package org.satelliteeavesdropper.app.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.BuildConfig
import java.nio.ByteBuffer
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import java.security.MessageDigest

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
        // Recreated activities must not inflate two large catalogs concurrently.
        loadLock.withLock { loadLocked(refresh) { coroutineContext.ensureActive() } }
    }

    private fun loadLocked(refresh: Boolean, checkActive: () -> Unit): CatalogLoadResult {
        checkActive()
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
                    val (compressed, sequenceHeader) = download("$baseUrl/v1/catalog.json.gz", SignedCatalogVerifier.MAX_COMPRESSED_BYTES, checkActive)
                    val sequence = sequenceHeader?.toLongOrNull()
                    require(sequence != null && sequence > 0) { "Missing catalog sequence header" }
                    val (sig, _) = download("$baseUrl/v1/catalog.sig?sequence=$sequence", SignedCatalogVerifier.MAX_SIGNATURE_BYTES, checkActive)
                    val existingSequence = readCached(verifier, key, checkActive)?.sequence
                    val manifest = verifier.verify(compressed, sig, sequence, checkActive)
                    require(existingSequence == null || manifest.sequence >= existingSequence) {
                        "Catalog sequence moved backwards"
                    }
                    val cacheRecord = ByteBuffer.allocate(8 + sig.size + compressed.size)
                        .putInt(0x53415431).putInt(sig.size).put(sig).put(compressed).array()
                    checkActive()
                    verifiedCache.writeAtomically(cacheRecord)
                    rememberVerified(cacheRecord, key, manifest)
                    return CatalogLoadResult(manifest, CatalogSource.LIVE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    warning = "Catalog refresh failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
        if (verifier != null) {
            readCached(verifier, key, checkActive)?.let { return CatalogLoadResult(it, CatalogSource.CACHED, warning) }
        }
        val sample = context.assets.open("sample_catalog.json").bufferedReader().use { it.readText() }
        checkActive()
        return CatalogLoadResult(
            CatalogParser.parse(sample),
            CatalogSource.DEMO,
            warning ?: "Demo catalog only. Configure a signed catalog endpoint to use current passes.",
        )
    }

    private fun readCached(
        verifier: SignedCatalogVerifier,
        key: String,
        checkActive: () -> Unit,
    ): CatalogManifest? {
        return try {
            checkActive()
            val bytes = verifiedCache.openRead().use { input ->
                val length = input.channel.size()
                val limit = 8L + SignedCatalogVerifier.MAX_SIGNATURE_BYTES + SignedCatalogVerifier.MAX_COMPRESSED_BYTES
                require(length in 9..limit) { "Invalid catalog cache size" }
                val snapshot = ByteArray(length.toInt())
                var offset = 0
                while (offset < snapshot.size) {
                    checkActive()
                    val count = input.read(snapshot, offset, minOf(16_384, snapshot.size - offset))
                    require(count > 0) { "Truncated catalog cache" }
                    offset += count
                }
                require(input.read() == -1) { "Catalog cache grew during read" }
                snapshot
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            checkActive()
            // Reuse only bytes already authenticated with this exact pinned key. The weak
            // reference shares the screen graph without retaining it after screen disposal.
            if (verifiedKey == key && verifiedDigest?.contentEquals(digest) == true) {
                verifiedManifest?.get()?.let { return it }
            }
            val record = ByteBuffer.wrap(bytes)
            require(record.remaining() >= 8 && record.int == 0x53415431) { "Invalid catalog cache" }
            val signatureLength = record.int
            require(signatureLength in 1..SignedCatalogVerifier.MAX_SIGNATURE_BYTES && record.remaining() > signatureLength) {
                "Invalid catalog cache lengths"
            }
            val sig = ByteArray(signatureLength).also { record.get(it) }
            val compressed = ByteArray(record.remaining()).also { record.get(it) }
            verifier.verify(compressed, sig, checkActive = checkActive).also {
                verifiedKey = key
                verifiedDigest = digest
                verifiedManifest = WeakReference(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("CatalogRepository", "Could not use cached signed catalog", error)
            null
        }
    }

    private fun rememberVerified(bytes: ByteArray, key: String, manifest: CatalogManifest) {
        verifiedKey = key
        verifiedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
        verifiedManifest = WeakReference(manifest)
    }

    companion object {
        private val loadLock = Mutex()
        private var verifiedKey: String? = null
        private var verifiedDigest: ByteArray? = null
        private var verifiedManifest: WeakReference<CatalogManifest>? = null
    }

    private fun download(address: String, maxBytes: Int, checkActive: () -> Unit): Pair<ByteArray, String?> {
        checkActive()
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream, application/json")
        }
        try {
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val sequence = connection.getHeaderField("X-Catalog-Sequence")
            val bytes = connection.inputStream.use { input -> input.readLimited(maxBytes, checkActive) }
            checkActive()
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

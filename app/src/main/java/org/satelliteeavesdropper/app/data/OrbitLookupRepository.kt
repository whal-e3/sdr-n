package org.satelliteeavesdropper.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.satelliteeavesdropper.orbit.OmmElements
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** An orbit-only lookup never supplies a radio profile or permission to receive. */
sealed interface OrbitLookupResult {
    val noradId: String
    val checkedAt: Instant
    val fromCache: Boolean

    data class Found(
        override val noradId: String,
        override val checkedAt: Instant,
        override val fromCache: Boolean,
        val satellite: SatelliteRecord,
        val fetchedAt: Instant,
        val provenance: String,
        val warning: String?,
    ) : OrbitLookupResult

    data class Unavailable(
        override val noradId: String,
        override val checkedAt: Instant,
        override val fromCache: Boolean,
        val message: String,
        val httpStatus: Int? = null,
        val retryAfter: Instant? = null,
    ) : OrbitLookupResult
}

data class OrbitLookupHttpResponse(val status: Int, val body: ByteArray)

fun interface OrbitLookupFetcher {
    suspend fun fetch(noradId: String): OrbitLookupHttpResponse
}

/** Fetches one CelesTrak CATNR record. HTTP redirects and non-200 responses are not retried. */
object CelestrakOrbitFetcher : OrbitLookupFetcher {
    private const val MAX_RESPONSE_BYTES = 65_536

    override suspend fun fetch(noradId: String): OrbitLookupHttpResponse = withContext(Dispatchers.IO) {
        val url = URL("https://celestrak.org/NORAD/elements/gp.php?CATNR=$noradId&FORMAT=JSON")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status != 200) return@withContext OrbitLookupHttpResponse(status, ByteArray(0))
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "CelesTrak response is too large" }
                    output.write(buffer, 0, count)
                }
            }
            OrbitLookupHttpResponse(status, output.toByteArray())
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Caches user-requested orbits that are absent from the signed catalog. Call [lookupNoradId]
 * only from an explicit lookup/refresh action, not from composition or a background timer.
 * One request per NORAD ID is allowed in each two-hour interval, including failed requests.
 */
class OrbitLookupRepository(
    private val directory: File,
    private val fetcher: OrbitLookupFetcher = CelestrakOrbitFetcher,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(context: Context) : this(File(context.filesDir, "orbit-lookups"))

    suspend fun lookupNoradId(rawId: String): OrbitLookupResult = withContext(Dispatchers.IO) {
        val id = normalizedId(rawId)
            ?: return@withContext OrbitLookupResult.Unavailable(
                noradId = rawId.trim(),
                checkedAt = clock.instant(),
                fromCache = false,
                message = "Enter a positive NORAD ID with at most nine digits.",
            )
        // Serializing lookups also prevents two simultaneous taps from issuing duplicate requests.
        requestMutex.withLock {
            val now = clock.instant()
            val previous = read(id)
            if (previous != null && withinCooldown(previous.checkedAt, now)) {
                return@withLock previous.result(id, now, fromCache = true)
            }
            val started = Stored(
                checkedAt = now,
                status = "started",
                httpStatus = null,
                message = "The previous lookup did not complete.",
                lastGood = previous?.lastGood,
            )
            // Record the attempt before sending it, so a crash cannot trigger a second request.
            try {
                write(id, started)
            } catch (error: Exception) {
                return@withLock OrbitLookupResult.Unavailable(
                    noradId = id,
                    checkedAt = now,
                    fromCache = false,
                    message = "Could not save lookup state; request was not sent: ${error.message.orEmpty()}",
                )
            }
            val completed = try {
                val response = fetcher.fetch(id)
                if (response.status != 200) {
                    started.copy(
                        status = "http-error",
                        httpStatus = response.status,
                        message = "CelesTrak returned HTTP ${response.status}. The lookup stopped without retrying.",
                    )
                } else {
                    try {
                        val satellite = parseSatellite(id, response.body)
                        val oldSatellite = previous?.lastGood?.let { good -> runCatching {
                            parseSatellite(id, good.omm.toString().toByteArray(Charsets.UTF_8), singleObject = true)
                        }.getOrNull() }
                        val olderThanCached = oldSatellite?.orbitElements()?.epoch?.isAfter(
                            requireNotNull(satellite.orbitElements()).epoch,
                        ) == true
                        started.copy(
                            status = if (olderThanCached) "older-response" else "found",
                            message = if (olderThanCached) "CelesTrak returned older elements; retained the newer cached orbit." else null,
                            lastGood = if (olderThanCached) requireNotNull(previous?.lastGood) else Good(now, satellite.omm),
                        )
                    } catch (error: Exception) {
                        started.copy(
                            status = "invalid-response",
                            message = "CelesTrak returned an unusable orbit: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                started.copy(
                    status = "network-error",
                    message = "CelesTrak lookup failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            val persistenceWarning = runCatching { write(id, completed) }.exceptionOrNull()
            val result = completed.result(id, now, fromCache = false)
            if (persistenceWarning == null) result else result.withWarning(
                "Could not save lookup result: ${persistenceWarning.message.orEmpty()}",
            )
        }
    }

    /** Previously fetched orbit-only records for a startup tracking list; no network access. */
    suspend fun cachedRecords(): List<OrbitLookupResult.Found> = withContext(Dispatchers.IO) {
        val now = clock.instant()
        directory.listFiles { file -> file.isFile && file.name.matches(Regex("[1-9][0-9]{0,8}\\.json")) }
            .orEmpty()
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".json")
                read(id)?.result(id, now, fromCache = true) as? OrbitLookupResult.Found
            }
            .sortedBy { it.noradId.toLong() }
    }

    private fun Stored.result(id: String, now: Instant, fromCache: Boolean): OrbitLookupResult {
        val good = lastGood
        if (good != null) {
            val record = runCatching {
                parseSatellite(id, good.omm.toString().toByteArray(Charsets.UTF_8), singleObject = true)
            }.getOrNull()
            if (record != null) {
                val stale = Duration.between(record.orbitElements()!!.epoch, now).toDays() >= 7
                val warnings = listOfNotNull(
                    message?.takeUnless { status == "found" },
                    if (stale) "Orbital elements are over seven days old; pass predictions may be inaccurate." else null,
                )
                return OrbitLookupResult.Found(
                    noradId = id,
                    checkedAt = checkedAt,
                    fromCache = fromCache,
                    satellite = record,
                    fetchedAt = good.fetchedAt,
                    provenance = PROVENANCE,
                    warning = warnings.joinToString(" ").ifEmpty { null },
                )
            }
        }
        return OrbitLookupResult.Unavailable(
            noradId = id,
            checkedAt = checkedAt,
            fromCache = fromCache,
            message = if (good != null) "The saved orbit is unusable. No request was repeated during the two-hour limit."
                else message ?: "No usable orbit was returned.",
            httpStatus = httpStatus,
            retryAfter = checkedAt.plus(COOLDOWN),
        )
    }

    private fun OrbitLookupResult.withWarning(extra: String): OrbitLookupResult = when (this) {
        is OrbitLookupResult.Found -> copy(warning = listOfNotNull(warning, extra).joinToString(" "))
        is OrbitLookupResult.Unavailable -> copy(message = "$message $extra")
    }

    private fun read(id: String): Stored? = runCatching {
        val file = File(directory, "$id.json")
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return@runCatching null
        val json = JSONObject(file.readText())
        require(json.getInt("schemaVersion") == 1 && json.getString("noradId") == id)
        val lastGood = json.optJSONObject("lastGood")?.let {
            Good(Instant.parse(it.getString("fetchedAt")), it.getJSONObject("omm"))
        }
        Stored(
            checkedAt = Instant.parse(json.getString("checkedAt")),
            status = json.getString("status"),
            httpStatus = if (json.isNull("httpStatus")) null else json.getInt("httpStatus"),
            message = if (json.isNull("message")) null else json.getString("message"),
            lastGood = lastGood,
        )
    }.getOrNull()

    private fun write(id: String, stored: Stored) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create orbit lookup cache directory" }
        val target = File(directory, "$id.json")
        val temp = File(directory, "$id.json.tmp")
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("noradId", id)
            .put("checkedAt", stored.checkedAt.toString())
            .put("status", stored.status)
            .put("httpStatus", stored.httpStatus ?: JSONObject.NULL)
            .put("message", stored.message ?: JSONObject.NULL)
        stored.lastGood?.let {
            json.put("lastGood", JSONObject().put("fetchedAt", it.fetchedAt.toString()).put("omm", it.omm))
        }
        try {
            temp.writeText(json.toString())
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private data class Good(val fetchedAt: Instant, val omm: JSONObject)
    private data class Stored(
        val checkedAt: Instant,
        val status: String,
        val httpStatus: Int?,
        val message: String?,
        val lastGood: Good?,
    )

    companion object {
        const val PROVENANCE = "CelesTrak GP direct lookup · orbit tracking only · no receive profile"
        private val COOLDOWN: Duration = Duration.ofHours(2)
        private const val MAX_CACHE_BYTES = 100_000L
        private val requestMutex = Mutex()

        private fun normalizedId(raw: String): String? {
            val text = raw.trim()
            if (!text.matches(Regex("[0-9]{1,9}"))) return null
            val normalized = text.trimStart('0').ifEmpty { "0" }
            return normalized.takeIf { it != "0" }
        }

        private fun withinCooldown(checkedAt: Instant, now: Instant): Boolean =
            now.isBefore(checkedAt.plus(COOLDOWN))

        private fun parseSatellite(id: String, bytes: ByteArray, singleObject: Boolean = false): SatelliteRecord {
            require(bytes.size <= 65_536) { "Response is too large" }
            val content = bytes.toString(Charsets.UTF_8)
            val omm = if (singleObject) JSONObject(content) else JSONArray(content).let { array ->
                require(array.length() == 1) { "Expected one orbital record for NORAD $id" }
                array.getJSONObject(0)
            }
            val fields = buildMap<String, Any?> {
                for (key in omm.keys()) put(key, if (omm.isNull(key)) null else omm.get(key))
            }
            val elements = OmmElements.fromCelestrakFields(fields)
            require(elements.noradId == id) { "CelesTrak returned NORAD ${elements.noradId} for $id" }
            val name = omm.optString("OBJECT_NAME").trim().take(120).ifEmpty { "NORAD $id" }
            return SatelliteRecord(id, name, emptyList(), omm, emptyList())
        }
    }
}

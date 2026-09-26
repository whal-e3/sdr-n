package org.satelliteeavesdropper.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

data class OrbitImportSummary(
    val sourceName: String,
    val format: OrbitImportFormat,
    val importedAt: Instant,
    val inputRecords: Int,
    val acceptedRecords: Int,
    val rejectedRecords: Int,
    /** Repeated NORAD IDs within this selected file. */
    val duplicateRecords: Int,
    /** Newer repeats within this selected file. */
    val inFileReplacementRecords: Int,
    /** IDs absent from all previously imported files. */
    val addedRecords: Int,
    /** Previously saved IDs replaced with an equal or newer epoch from this file. */
    val replacedRecords: Int,
    /** Distinct valid IDs within this selected file. */
    val uniqueRecords: Int,
    /** Distinct records saved after this import, including earlier files. */
    val savedRecords: Int,
    val warnings: List<String>,
) {
    val provenance: String get() = "User file: $sourceName · imported $importedAt · tracking only"
}

data class OrbitImportSnapshot(
    /** Most recent import; [sources] retains every completed import's summary. */
    val summary: OrbitImportSummary,
    val records: List<SatelliteRecord>,
    val sources: List<OrbitImportSummary>,
    /** NORAD ID to index in [sources] for each retained record. */
    val recordSourceIndices: Map<String, Int>,
) {
    fun sourceFor(noradId: String): OrbitImportSummary? =
        recordSourceIndices[noradId]?.let(sources::getOrNull)
}

/** Copies an explicitly chosen SAF file into a private, atomic tracking-only cache. */
class OrbitImportRepository(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val contentResolver: ContentResolver? = null,
) {
    constructor(context: Context) : this(File(context.filesDir, "orbit-imports"), Clock.systemUTC(), context.contentResolver)

    suspend fun importFromUri(uri: Uri): OrbitImportSnapshot = withContext(Dispatchers.IO) {
        val resolver = requireNotNull(contentResolver) { "An Android Context is required for URI import" }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "orbital-data"
        val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot open the selected orbital data file")
        try {
            importStream(input, name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalArgumentException("Selected ${safeSourceName(name)}: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    /** Also used by tests and non-Android callers; the stream is closed on return. */
    suspend fun importStream(input: InputStream, displayName: String): OrbitImportSnapshot = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            input.use { stream ->
                val previous = read()
                val incoming = LinkedHashMap<String, SatelliteRecord>()
                var duplicateRecords = 0
                var inFileReplacementRecords = 0
                val job = currentCoroutineContext()
                val formatHint = when {
                    displayName.trim().endsWith(".json", ignoreCase = true) -> OrbitImportFormat.OMM_JSON
                    displayName.trim().endsWith(".csv", ignoreCase = true) -> OrbitImportFormat.OMM_CSV
                    else -> null
                }
                val report = OrbitImportParser.parse(stream, formatHint) { record ->
                    job.ensureActive()
                    val existing = incoming[record.noradId]
                    if (existing != null) {
                        duplicateRecords++
                        val oldEpoch = requireNotNull(existing.orbitElements()).epoch
                        val newEpoch = requireNotNull(record.orbitElements()).epoch
                        if (!newEpoch.isBefore(oldEpoch)) {
                            incoming[record.noradId] = record
                            inFileReplacementRecords++
                        }
                    } else {
                        require(incoming.size < MAX_UNIQUE_RECORDS) {
                            "The file has more than $MAX_UNIQUE_RECORDS unique orbital records"
                        }
                        incoming[record.noradId] = record
                    }
                }
                require(incoming.isNotEmpty()) { "No valid orbital records were found; previous imports were kept" }
                val sources = previous?.sources.orEmpty().toMutableList()
                require(sources.size < MAX_SOURCES) { "Too many orbital data files; clear old imports first" }
                val sourceIndex = sources.size
                val records = LinkedHashMap<String, SatelliteRecord>()
                previous?.records?.forEach { records[it.noradId] = it }
                val sourceIndices = previous?.recordSourceIndices?.toMutableMap() ?: HashMap()
                var addedRecords = 0
                var replacedRecords = 0
                for ((id, record) in incoming) {
                    val old = records[id]
                    if (old == null) {
                        require(records.size < MAX_UNIQUE_RECORDS) {
                            "Combined imports exceed $MAX_UNIQUE_RECORDS unique orbital records"
                        }
                        records[id] = record
                        sourceIndices[id] = sourceIndex
                        addedRecords++
                    } else if (!requireNotNull(record.orbitElements()).epoch.isBefore(requireNotNull(old.orbitElements()).epoch)) {
                        records[id] = record
                        sourceIndices[id] = sourceIndex
                        replacedRecords++
                    }
                }
                val summary = OrbitImportSummary(
                    sourceName = safeSourceName(displayName),
                    format = report.format,
                    importedAt = clock.instant(),
                    inputRecords = report.inputRecords,
                    acceptedRecords = report.inputRecords - report.rejectedRecords,
                    rejectedRecords = report.rejectedRecords,
                    duplicateRecords = duplicateRecords,
                    inFileReplacementRecords = inFileReplacementRecords,
                    addedRecords = addedRecords,
                    replacedRecords = replacedRecords,
                    uniqueRecords = incoming.size,
                    savedRecords = records.size,
                    warnings = report.warnings,
                )
                sources += summary
                val snapshot = OrbitImportSnapshot(
                    summary, records.values.sortedBy { it.noradId.toLong() }, sources, sourceIndices,
                )
                write(snapshot)
                snapshot
            }
        }
    }

    /** Returns the last completed import without reopening the original SAF URI. */
    suspend fun load(): OrbitImportSnapshot? = withContext(Dispatchers.IO) {
        cacheMutex.withLock { read() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheMutex.withLock { Files.deleteIfExists(File(directory, CACHE_NAME).toPath()) }
    }

    private fun write(snapshot: OrbitImportSnapshot) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create the orbital import directory" }
        val target = File(directory, CACHE_NAME)
        val temp = File(directory, "$CACHE_NAME.tmp")
        try {
            temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(JSONObject()
                    .put("schemaVersion", 2)
                    .put("savedRecords", snapshot.records.size)
                    .put("sources", JSONArray(snapshot.sources.map(::summaryToJson)))
                    .toString())
                for (record in snapshot.records) {
                    writer.appendLine(JSONObject()
                        .put("noradId", record.noradId)
                        .put("sourceIndex", requireNotNull(snapshot.recordSourceIndices[record.noradId]))
                        .put("omm", record.omm)
                        .toString())
                }
            }
            require(temp.length() <= MAX_CACHE_BYTES) { "The orbital import cache exceeds $MAX_CACHE_BYTES bytes" }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun read(): OrbitImportSnapshot? {
        val file = File(directory, CACHE_NAME)
        if (!file.isFile) return null
        require(file.length() in 1..MAX_CACHE_BYTES) { "Saved orbital import is too large or empty" }
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val header = JSONObject(readBoundedLine(reader) ?: error("Saved import is missing metadata"))
            require(header.getInt("schemaVersion") == 2) { "Unsupported saved orbital import" }
            val sourcesJson = header.getJSONArray("sources")
            require(sourcesJson.length() in 1..MAX_SOURCES) { "Saved orbital import has invalid source count" }
            val sources = (0 until sourcesJson.length()).map { index ->
                summaryFromJson(sourcesJson.getJSONObject(index))
            }
            var priorCount = 0
            for (source in sources) {
                require(source.savedRecords == priorCount + source.addedRecords) { "Saved orbital import counts are inconsistent" }
                priorCount = source.savedRecords
            }
            val savedRecords = header.getInt("savedRecords")
            require(savedRecords in 1..MAX_UNIQUE_RECORDS && savedRecords == sources.last().savedRecords) {
                "Saved orbital import has invalid record count"
            }
            val records = ArrayList<SatelliteRecord>(savedRecords)
            val sourceIndices = HashMap<String, Int>(savedRecords)
            while (true) {
                val line = readBoundedLine(reader) ?: break
                val json = JSONObject(line)
                val record = OrbitImportParser.parseOmmObject(json.getJSONObject("omm"))
                require(json.getString("noradId") == record.noradId) { "Saved orbital import has mismatched NORAD IDs" }
                require(record.transmitters.isEmpty())
                val sourceIndex = json.getInt("sourceIndex")
                require(sourceIndex in sources.indices) { "Saved orbital import has an invalid source reference" }
                records += record
                require(sourceIndices.put(record.noradId, sourceIndex) == null) {
                    "Saved orbital import has duplicate IDs"
                }
                require(records.size <= MAX_UNIQUE_RECORDS) { "Saved orbital import exceeds record limit" }
            }
            require(records.size == savedRecords) { "Saved orbital import is incomplete" }
            return OrbitImportSnapshot(sources.last(), records, sources, sourceIndices)
        }
    }

    private fun summaryToJson(summary: OrbitImportSummary): JSONObject = JSONObject()
        .put("sourceName", summary.sourceName)
        .put("format", summary.format.name)
        .put("importedAt", summary.importedAt.toString())
        .put("inputRecords", summary.inputRecords)
        .put("acceptedRecords", summary.acceptedRecords)
        .put("rejectedRecords", summary.rejectedRecords)
        .put("duplicateRecords", summary.duplicateRecords)
        .put("inFileReplacementRecords", summary.inFileReplacementRecords)
        .put("addedRecords", summary.addedRecords)
        .put("replacedRecords", summary.replacedRecords)
        .put("uniqueRecords", summary.uniqueRecords)
        .put("savedRecords", summary.savedRecords)
        .put("warnings", JSONArray(summary.warnings))

    private fun summaryFromJson(json: JSONObject): OrbitImportSummary {
        val warnings = json.getJSONArray("warnings").let { array ->
            require(array.length() <= 5) { "Saved orbital import has too many warnings" }
            (0 until array.length()).map(array::getString)
        }
        val summary = OrbitImportSummary(
            sourceName = safeSourceName(json.getString("sourceName")),
            format = OrbitImportFormat.valueOf(json.getString("format")),
            importedAt = Instant.parse(json.getString("importedAt")),
            inputRecords = json.getInt("inputRecords"),
            acceptedRecords = json.getInt("acceptedRecords"),
            rejectedRecords = json.getInt("rejectedRecords"),
            duplicateRecords = json.getInt("duplicateRecords"),
            inFileReplacementRecords = json.getInt("inFileReplacementRecords"),
            addedRecords = json.getInt("addedRecords"),
            replacedRecords = json.getInt("replacedRecords"),
            uniqueRecords = json.getInt("uniqueRecords"),
            savedRecords = json.getInt("savedRecords"),
            warnings = warnings,
        )
        require(summary.inputRecords in 1..OrbitImportParser.MAX_INPUT_RECORDS &&
            summary.acceptedRecords in 1..summary.inputRecords &&
            summary.rejectedRecords >= 0 &&
            summary.acceptedRecords + summary.rejectedRecords == summary.inputRecords &&
            summary.duplicateRecords >= 0 &&
            summary.uniqueRecords == summary.acceptedRecords - summary.duplicateRecords &&
            summary.inFileReplacementRecords in 0..summary.duplicateRecords &&
            summary.addedRecords >= 0 && summary.replacedRecords >= 0 &&
            summary.addedRecords + summary.replacedRecords <= summary.uniqueRecords &&
            summary.savedRecords in 1..MAX_UNIQUE_RECORDS) {
            "Saved orbital import has inconsistent counts"
        }
        return summary
    }

    private fun readBoundedLine(reader: java.io.Reader): String? {
        val text = StringBuilder()
        while (true) {
            val code = reader.read()
            if (code == -1) return text.takeIf { it.isNotEmpty() }?.toString()
            if (code == '\n'.code) return text.toString()
            if (code != '\r'.code) text.append(code.toChar())
            require(text.length <= MAX_CACHE_LINE_CHARS) { "Saved orbital import has an oversized record" }
        }
    }

    private fun safeSourceName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .filter { it >= ' ' && it != '\u007f' }
        .trim().take(120).ifEmpty { "orbital-data" }

    companion object {
        const val MAX_UNIQUE_RECORDS = 75_000
        private const val MAX_SOURCES = 64
        private const val CACHE_NAME = "orbit-imports.jsonl"
        private const val MAX_CACHE_BYTES = 96L * 1024 * 1024
        private const val MAX_CACHE_LINE_CHARS = 262_144
        private val cacheMutex = Mutex()
    }
}

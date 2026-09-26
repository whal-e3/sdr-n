package org.satelliteeavesdropper.app.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.satelliteeavesdropper.orbit.OmmElements
import java.io.Reader
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.DateTimeException
import java.time.ZoneOffset

data class CatalogManifest(
    val sequence: Long,
    val generatedAt: Instant,
    val sourceUpdatedAt: Instant,
    val satellites: List<SatelliteRecord>,
    val attribution: List<String>,
)

data class SatelliteRecord(
    val noradId: String,
    val name: String,
    val aliases: List<String>,
    val omm: JSONObject,
    val transmitters: List<TransmitterRecord>,
) {
    /** A source-reported date has no exact event time; use its UTC start conservatively. */
    val recordedDecayDate: LocalDate? = (omm.opt("DECAY_DATE") as? String)
        ?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            try { LocalDate.parse(value) } catch (_: DateTimeException) { null }
        }
    private val recordedDecayInstant = recordedDecayDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

    fun isKnownDecayedAt(now: Instant): Boolean =
        recordedDecayInstant?.let { !now.isBefore(it) } ?: false

    /** Historical elements remain browsable; extrapolation never extends beyond known decay. */
    fun predictionEndBeforeDecay(start: Instant, end: Instant): Instant? {
        val boundedEnd = recordedDecayInstant?.let { minOf(end, it) } ?: end
        return boundedEnd.takeIf { start.isBefore(it) }
    }

    fun orbitElements(): OmmElements? = try {
        OmmElements(
            noradId = noradId,
            epoch = Instant.parse(omm.getString("EPOCH").let { if (it.endsWith("Z")) it else "${it}Z" }),
            meanMotionRevolutionsPerDay = omm.getDouble("MEAN_MOTION"),
            eccentricity = omm.getDouble("ECCENTRICITY"),
            inclinationDegrees = omm.getDouble("INCLINATION"),
            raanDegrees = omm.getDouble("RA_OF_ASC_NODE"),
            argumentOfPerigeeDegrees = omm.getDouble("ARG_OF_PERICENTER"),
            meanAnomalyDegrees = omm.getDouble("MEAN_ANOMALY"),
            bStar = omm.optDouble("BSTAR", 0.0),
            meanMotionDot = omm.optDouble("MEAN_MOTION_DOT", 0.0),
            meanMotionDdot = omm.optDouble("MEAN_MOTION_DDOT", 0.0),
            objectId = omm.optString("OBJECT_ID").ifEmpty { null },
        )
    } catch (_: Exception) {
        null
    }
}

data class TransmitterRecord(
    val id: String,
    val frequencyHz: Long,
    val bandwidthHz: Long?,
    val mode: String,
    val baud: Int?,
    val status: String,
    val verifiedAt: Instant?,
    val decoderId: String?,
    val captureRateSps: Int?,
    val antenna: String?,
    val policy: String,
    val evidenceUrls: List<String>,
) {
    val mayReceive: Boolean get() = policy == "public" || policy == "amateur"
    val policyAndAntennaLabel: String
        get() = listOfNotNull(policy, antenna?.takeIf(String::isNotBlank)).joinToString(" · ")
}

object CatalogParser {
    private const val MAX_SATELLITES = 100_000
    private const val MAX_OMM_DEPTH = 8
    private val commonOmmKeys = listOf(
        "OBJECT_NAME", "OBJECT_ID", "EPOCH", "MEAN_MOTION", "ECCENTRICITY", "INCLINATION",
        "RA_OF_ASC_NODE", "ARG_OF_PERICENTER", "MEAN_ANOMALY", "EPHEMERIS_TYPE",
        "CLASSIFICATION_TYPE", "NORAD_CAT_ID", "ELEMENT_SET_NO", "REV_AT_EPOCH", "BSTAR",
        "MEAN_MOTION_DOT", "MEAN_MOTION_DDOT", "SEMIMAJOR_AXIS", "PERIOD", "APOAPSIS",
        "PERIAPSIS", "OBJECT_TYPE", "RCS_SIZE", "COUNTRY_CODE", "LAUNCH_DATE", "SITE",
        "DECAY_DATE", "FILE", "GP_ID", "TLE_LINE0", "TLE_LINE1", "TLE_LINE2",
    ).associateBy { it }

    fun parse(json: String): CatalogManifest = parse(StringReader(json))

    /** Reads one satellite at a time; no full manifest JSON tree or inflated byte array is retained. */
    fun parse(reader: Reader): CatalogManifest = JsonReader(reader).use { json ->
        var schemaVersion: Int? = null
        var sequence: Long? = null
        var generatedAt: Instant? = null
        var sourceUpdatedAt: Instant? = null
        var satellites: List<SatelliteRecord>? = null
        var attribution: List<String> = emptyList()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "schemaVersion" -> schemaVersion = json.nextInt()
                "sequence" -> sequence = json.nextLong()
                "generatedAt" -> generatedAt = Instant.parse(json.nextString())
                "sourceUpdatedAt" -> sourceUpdatedAt = Instant.parse(json.nextString())
                "satellites" -> satellites = readSatellites(json)
                "attribution" -> attribution = readOptionalStrings(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        require(json.peek() == JsonToken.END_DOCUMENT) { "Trailing catalog data" }
        require(schemaVersion == 1) { "Unsupported catalog schema" }
        CatalogManifest(
            sequence = sequence ?: throw JSONException("No value for sequence"),
            generatedAt = generatedAt ?: throw JSONException("No value for generatedAt"),
            sourceUpdatedAt = sourceUpdatedAt ?: throw JSONException("No value for sourceUpdatedAt"),
            satellites = satellites ?: throw JSONException("No value for satellites"),
            attribution = attribution,
        )
    }

    private fun readSatellites(json: JsonReader): List<SatelliteRecord> {
        if (json.peek() != JsonToken.BEGIN_ARRAY) throw JSONException("satellites must be an array")
        val records = ArrayList<SatelliteRecord>()
        json.beginArray()
        while (json.hasNext()) {
            require(records.size < MAX_SATELLITES) { "Catalog exceeds satellite count limit" }
            records.add(readSatellite(json))
        }
        json.endArray()
        return records
    }

    private fun readSatellite(json: JsonReader): SatelliteRecord {
        var noradId: String? = null
        var name: String? = null
        var aliases: List<String> = emptyList()
        var omm: JSONObject? = null
        var transmitters: List<TransmitterRecord>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "noradId" -> noradId = json.nextString()
                "name" -> name = json.nextString()
                "aliases" -> aliases = readOptionalStrings(json)
                "omm" -> omm = readOmm(json)
                "transmitters" -> transmitters = readTransmitters(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return SatelliteRecord(
            noradId = noradId ?: throw JSONException("No value for noradId"),
            name = name ?: throw JSONException("No value for name"),
            aliases = aliases,
            omm = omm ?: throw JSONException("No value for omm"),
            transmitters = transmitters ?: throw JSONException("No value for transmitters"),
        )
    }

    private fun readTransmitters(json: JsonReader): List<TransmitterRecord> {
        if (json.peek() != JsonToken.BEGIN_ARRAY) throw JSONException("transmitters must be an array")
        val records = ArrayList<TransmitterRecord>()
        json.beginArray()
        while (json.hasNext()) records.add(readTransmitter(json))
        json.endArray()
        return records
    }

    private fun readTransmitter(json: JsonReader): TransmitterRecord {
        var id: String? = null
        var frequencyHz: Long? = null
        var bandwidthHz: Long? = null
        var mode = ""
        var baud: Int? = null
        var status = ""
        var verifiedAt: Instant? = null
        var decoderId: String? = null
        var captureRateSps: Int? = null
        var antenna: String? = null
        var policy = "restricted"
        var evidenceUrls: List<String> = emptyList()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "id" -> id = json.nextString()
                "frequencyHz" -> frequencyHz = json.nextLong()
                "bandwidthHz" -> bandwidthHz = readOptionalLong(json)
                "mode" -> mode = readOptionalString(json) ?: ""
                "baud" -> baud = readOptionalInt(json)
                "status" -> status = readOptionalString(json) ?: ""
                "verifiedAt" -> verifiedAt = readOptionalString(json)?.takeIf(String::isNotBlank)?.let(Instant::parse)
                "decoderId" -> decoderId = readOptionalString(json)?.takeIf(String::isNotBlank)
                "captureRateSps" -> captureRateSps = readOptionalInt(json)
                "antenna" -> antenna = readAntenna(json)
                "policy" -> policy = readOptionalString(json) ?: "restricted"
                "evidenceUrls" -> evidenceUrls = readOptionalStrings(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return TransmitterRecord(
            id = id ?: throw JSONException("No value for id"),
            frequencyHz = frequencyHz ?: throw JSONException("No value for frequencyHz"),
            bandwidthHz = bandwidthHz,
            mode = mode,
            baud = baud,
            status = status,
            verifiedAt = verifiedAt,
            decoderId = decoderId,
            captureRateSps = captureRateSps,
            antenna = antenna,
            policy = policy,
            evidenceUrls = evidenceUrls,
        )
    }

    private fun readAntenna(json: JsonReader): String? = when (json.peek()) {
        JsonToken.STRING -> json.nextString().takeIf(String::isNotBlank)
        JsonToken.BEGIN_OBJECT -> {
            var description: String? = null
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "description") {
                    description = readOptionalString(json)?.takeIf(String::isNotBlank)
                } else json.skipValue()
            }
            json.endObject()
            description
        }
        else -> { json.skipValue(); null }
    }

    private fun readOptionalStrings(json: JsonReader): List<String> {
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue()
            return emptyList()
        }
        val strings = ArrayList<String>()
        json.beginArray()
        while (json.hasNext()) strings.add(json.nextString())
        json.endArray()
        return strings
    }

    private fun readOptionalString(json: JsonReader): String? = if (json.peek() == JsonToken.NULL) {
        json.nextNull()
        null
    } else json.nextString()

    private fun readOptionalLong(json: JsonReader): Long? = if (json.peek() == JsonToken.NULL) {
        json.nextNull()
        null
    } else json.nextLong()

    private fun readOptionalInt(json: JsonReader): Int? = if (json.peek() == JsonToken.NULL) {
        json.nextNull()
        null
    } else {
        val value = json.nextString()
        value.toIntOrNull() ?: value.toDoubleOrNull()?.let { number ->
            require(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
                "Catalog integer is outside supported range"
            }
            // org.json getInt historically truncated numeric SatNOGS baud values such as 977.52.
            number.toInt()
        } ?: throw JSONException("Invalid catalog integer")
    }

    private fun readOmm(json: JsonReader): JSONObject {
        if (json.peek() != JsonToken.BEGIN_OBJECT) throw JSONException("omm must be an object")
        return readJsonObject(json, depth = 0)
    }

    private fun readJsonObject(json: JsonReader, depth: Int): JSONObject {
        require(depth < MAX_OMM_DEPTH) { "Catalog OMM nesting exceeds limit" }
        val result = JSONObject()
        json.beginObject()
        while (json.hasNext()) {
            val name = json.nextName()
            // Each OMM repeats the same keys. Share those strings across all records.
            result.put(commonOmmKeys[name] ?: name, readJsonValue(json, depth + 1))
        }
        json.endObject()
        return result
    }

    private fun readJsonArray(json: JsonReader, depth: Int): JSONArray {
        require(depth < MAX_OMM_DEPTH) { "Catalog OMM nesting exceeds limit" }
        val result = JSONArray()
        json.beginArray()
        while (json.hasNext()) result.put(readJsonValue(json, depth + 1))
        json.endArray()
        return result
    }

    private fun readJsonValue(json: JsonReader, depth: Int): Any = when (json.peek()) {
        JsonToken.BEGIN_OBJECT -> readJsonObject(json, depth)
        JsonToken.BEGIN_ARRAY -> readJsonArray(json, depth)
        JsonToken.STRING -> json.nextString()
        JsonToken.NUMBER -> json.nextString().let { number ->
            number.toLongOrNull() ?: number.toDoubleOrNull()
            ?: throw JSONException("Invalid OMM number")
        }
        JsonToken.BOOLEAN -> json.nextBoolean()
        JsonToken.NULL -> { json.nextNull(); JSONObject.NULL }
        else -> throw JSONException("Invalid OMM value")
    }
}

package org.satelliteeavesdropper.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.satelliteeavesdropper.orbit.OmmElements
import java.time.Instant

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
    fun orbitElements(): OmmElements? = runCatching {
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
    }.getOrNull()
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
    val antenna: String,
    val policy: String,
    val evidenceUrls: List<String>,
) {
    val mayReceive: Boolean get() = policy == "public" || policy == "amateur"
}

object CatalogParser {
    fun parse(json: String): CatalogManifest {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "Unsupported catalog schema" }
        val satellites = root.getJSONArray("satellites").mapObjects { item ->
            val transmitters = item.getJSONArray("transmitters").mapObjects { tx ->
                TransmitterRecord(
                    id = tx.getString("id"),
                    frequencyHz = tx.getLong("frequencyHz"),
                    bandwidthHz = tx.optLongOrNull("bandwidthHz"),
                    mode = tx.optString("mode"),
                    baud = tx.optIntOrNull("baud"),
                    status = tx.optString("status"),
                    verifiedAt = tx.optNullableString("verifiedAt")?.let(Instant::parse),
                    decoderId = tx.optNullableString("decoderId"),
                    captureRateSps = tx.optIntOrNull("captureRateSps"),
                    antenna = tx.optJSONObject("antenna")?.optString("description")
                        ?: tx.optString("antenna"),
                    policy = tx.optString("policy", "restricted"),
                    evidenceUrls = tx.optJSONArray("evidenceUrls")?.mapStrings().orEmpty(),
                )
            }
            SatelliteRecord(
                noradId = item.getString("noradId"),
                name = item.getString("name"),
                aliases = item.optJSONArray("aliases")?.mapStrings().orEmpty(),
                omm = item.getJSONObject("omm"),
                transmitters = transmitters,
            )
        }
        return CatalogManifest(
            sequence = root.getLong("sequence"),
            generatedAt = Instant.parse(root.getString("generatedAt")),
            sourceUpdatedAt = Instant.parse(root.getString("sourceUpdatedAt")),
            satellites = satellites,
            attribution = root.optJSONArray("attribution")?.mapStrings().orEmpty(),
        )
    }

    private fun JSONArray.mapStrings(): List<String> = (0 until length()).map(::getString)
    private inline fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
        (0 until length()).map { block(getJSONObject(it)) }
    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else getInt(key)
    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else getString(key).takeIf(String::isNotBlank)
}

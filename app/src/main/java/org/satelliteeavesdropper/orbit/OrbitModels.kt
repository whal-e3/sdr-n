package org.satelliteeavesdropper.orbit

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The SGP4 fields of a CelesTrak/CCSDS OMM record. Angles are degrees. */
data class OmmElements(
    val noradId: String,
    val epoch: Instant,
    val meanMotionRevolutionsPerDay: Double,
    val eccentricity: Double,
    val inclinationDegrees: Double,
    val raanDegrees: Double,
    val argumentOfPerigeeDegrees: Double,
    val meanAnomalyDegrees: Double,
    val bStar: Double = 0.0,
    val meanMotionDot: Double = 0.0,
    val meanMotionDdot: Double = 0.0,
    val objectId: String? = null,
) {
    init {
        require(noradId.matches(NORAD_ID_PATTERN) && noradId.toInt() > 0) {
            "NORAD_CAT_ID must be a positive decimal ID of at most nine digits"
        }
        require(meanMotionRevolutionsPerDay.isFinite() && meanMotionRevolutionsPerDay > 0.0)
        require(eccentricity.isFinite() && eccentricity >= 0.0 && eccentricity < 1.0)
        require(inclinationDegrees.isFinite() && inclinationDegrees in 0.0..180.0)
        require(raanDegrees.isFinite() && argumentOfPerigeeDegrees.isFinite() && meanAnomalyDegrees.isFinite())
        require(bStar.isFinite() && meanMotionDot.isFinite() && meanMotionDdot.isFinite())
    }

    companion object {
        private val NORAD_ID_PATTERN = Regex("[0-9]{1,9}")
        private val EPOCH_OFFSET_PATTERN = Regex("[+-]\\d\\d:\\d\\d$")

        /** Accepts numeric or string-valued CelesTrak OMM JSON fields. Missing optional drag fields are zero. */
        fun fromCelestrakFields(fields: Map<String, Any?>): OmmElements {
            fun required(name: String): String = fields[name]?.toString()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Missing OMM field: $name")
            fun number(name: String): Double = required(name).toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid OMM number: $name")
            fun optionalNumber(name: String): Double = fields[name]?.let {
                it.toString().toDoubleOrNull() ?: throw IllegalArgumentException("Invalid OMM number: $name")
            } ?: 0.0
            fun expectIfPresent(name: String, expected: String) {
                fields[name]?.let { require(it.toString() == expected) { "Unsupported OMM $name: $it" } }
            }
            expectIfPresent("CENTER_NAME", "EARTH")
            expectIfPresent("REF_FRAME", "TEME")
            expectIfPresent("TIME_SYSTEM", "UTC")
            expectIfPresent("MEAN_ELEMENT_THEORY", "SGP4")
            val rawId = fields["NORAD_CAT_ID"]
            val noradId = if (rawId is Number) {
                val value = rawId.toDouble()
                require(value.isFinite() && value > 0.0 && value <= 999_999_999.0 && value == value.toLong().toDouble()) {
                    "Invalid OMM NORAD_CAT_ID"
                }
                value.toLong().toString()
            } else {
                required("NORAD_CAT_ID")
            }
            // Space-Track GP JSON uses a space between the UTC date and time;
            // CelesTrak OMM JSON uses the ISO T separator. Both are UTC.
            val epochText = required("EPOCH").trim().replaceFirst(' ', 'T')
            val epoch = if (epochText.endsWith("Z") || epochText.contains(EPOCH_OFFSET_PATTERN)) {
                Instant.parse(epochText)
            } else {
                LocalDateTime.parse(epochText).toInstant(ZoneOffset.UTC)
            }
            return OmmElements(
                noradId = noradId,
                epoch = epoch,
                meanMotionRevolutionsPerDay = number("MEAN_MOTION"),
                eccentricity = number("ECCENTRICITY"),
                inclinationDegrees = number("INCLINATION"),
                raanDegrees = number("RA_OF_ASC_NODE"),
                argumentOfPerigeeDegrees = number("ARG_OF_PERICENTER"),
                meanAnomalyDegrees = number("MEAN_ANOMALY"),
                bStar = optionalNumber("BSTAR"),
                meanMotionDot = optionalNumber("MEAN_MOTION_DOT"),
                meanMotionDdot = optionalNumber("MEAN_MOTION_DDOT"),
                objectId = fields["OBJECT_ID"]?.toString(),
            )
        }
    }
}

data class ObserverLocation(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double = 0.0,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0)
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0)
        require(altitudeMeters.isFinite() && altitudeMeters >= -500.0 && altitudeMeters <= 100_000.0)
    }
}

data class SatelliteLook(
    val time: Instant,
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val slantRangeKm: Double,
    /** Positive when the satellite is moving away from the observer. */
    val rangeRateKmPerSecond: Double,
) {
    /** Frequency offset to apply to the nominal downlink for first-order Doppler correction. */
    fun dopplerShiftHz(frequencyHz: Double): Double {
        require(frequencyHz.isFinite() && frequencyHz > 0.0)
        return -frequencyHz * rangeRateKmPerSecond / 299_792.458
    }
}

data class SatellitePass(
    val aos: Instant,
    val tca: Instant,
    val los: Instant,
    val maximumElevationDegrees: Double,
    val aosAzimuthDegrees: Double,
    val losAzimuthDegrees: Double,
    /** The pass was already above the requested elevation at the beginning of the search window. */
    val beganBeforeWindow: Boolean,
    /** The pass remains above the requested elevation at the end of the search window. */
    val endsAfterWindow: Boolean,
)

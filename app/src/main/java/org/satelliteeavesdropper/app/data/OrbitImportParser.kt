package org.satelliteeavesdropper.app.data

import org.json.JSONObject
import org.satelliteeavesdropper.orbit.OmmElements
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.PushbackReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset

enum class OrbitImportFormat { TLE, OMM_JSON, OMM_CSV }

data class OrbitParseReport(
    val format: OrbitImportFormat,
    val inputRecords: Int,
    val rejectedRecords: Int,
    val warnings: List<String>,
)

/** A bounded streaming parser. Every emitted satellite is for orbit tracking only. */
object OrbitImportParser {
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    const val MAX_INPUT_RECORDS = 100_000
    private const val MAX_TLE_LINE_CHARS = 256
    private const val MAX_JSON_OBJECT_CHARS = 32_768
    private const val MAX_CSV_RECORD_CHARS = 32_768
    private const val MAX_CSV_COLUMNS = 128
    private const val MAX_WARNINGS = 5
    private val CSV_OMM_FIELDS = setOf(
        "OBJECT_NAME", "NORAD_CAT_ID", "EPOCH", "MEAN_MOTION", "ECCENTRICITY", "INCLINATION",
        "RA_OF_ASC_NODE", "ARG_OF_PERICENTER", "MEAN_ANOMALY", "MEAN_MOTION_DOT",
        "MEAN_MOTION_DDOT", "BSTAR", "OBJECT_ID", "CENTER_NAME", "REF_FRAME",
        "TIME_SYSTEM", "MEAN_ELEMENT_THEORY", "DECAY_DATE",
    )
    private val CSV_REQUIRED_FIELDS = setOf(
        "NORAD_CAT_ID", "EPOCH", "MEAN_MOTION", "ECCENTRICITY", "INCLINATION",
        "RA_OF_ASC_NODE", "ARG_OF_PERICENTER", "MEAN_ANOMALY",
    )

    fun parse(input: InputStream, accept: (SatelliteRecord) -> Unit): OrbitParseReport =
        parse(input, formatHint = null, accept = accept)

    /** The optional filename hint selects GP CSV or requires JSON; JSON content takes precedence. */
    fun parse(input: InputStream, formatHint: OrbitImportFormat?, accept: (SatelliteRecord) -> Unit): OrbitParseReport {
        val byteStream = PushbackInputStream(LimitedInputStream(input), 4)
        val prefix = IntArray(4)
        var length = 0
        while (length < prefix.size) {
            val value = byteStream.read()
            if (value == -1) break
            prefix[length++] = value
        }
        val (charset, skip) = when {
            length >= 3 && prefix[0] == 0xef && prefix[1] == 0xbb && prefix[2] == 0xbf ->
                StandardCharsets.UTF_8 to 3
            length >= 2 && prefix[0] == 0xfe && prefix[1] == 0xff ->
                StandardCharsets.UTF_16BE to 2
            length >= 2 && prefix[0] == 0xff && prefix[1] == 0xfe ->
                StandardCharsets.UTF_16LE to 2
            else -> StandardCharsets.UTF_8 to 0
        }
        for (index in length - 1 downTo skip) byteStream.unread(prefix[index])
        val reader = PushbackReader(BufferedReader(InputStreamReader(byteStream, charset), 8192), 2)
        val first = nextNonWhitespace(reader)
        require(first != -1) { "The orbital data file is empty" }
        require(first != '<'.code) {
            "This is an HTML webpage, not orbital data. Download raw GP CSV, OMM JSON or TLE text instead of saving a login page."
        }
        return if (first == '['.code || first == '{'.code) {
            parseOmmJson(reader, first, accept)
        } else if (formatHint == OrbitImportFormat.OMM_CSV) {
            reader.unread(first)
            parseOmmCsv(reader, accept)
        } else {
            require(formatHint != OrbitImportFormat.OMM_JSON) {
                "Selected JSON file does not start with '[' or '{' (first character U+${first.toString(16).uppercase().padStart(4, '0')})"
            }
            reader.unread(first)
            parseTle(reader, accept)
        }
    }

    /** CelesTrak and Space-Track GP CSV use OMM keyword columns, including 9-digit IDs. */
    private fun parseOmmCsv(reader: PushbackReader, accept: (SatelliteRecord) -> Unit): OrbitParseReport {
        val header = readCsvRecord(reader) ?: throw IllegalArgumentException("The GP CSV file is empty")
        require(header.size <= MAX_CSV_COLUMNS && header.size == header.distinct().size &&
            header.all { it.matches(Regex("[A-Z][A-Z0-9_]*")) } && header.containsAll(CSV_REQUIRED_FIELDS)) {
            "GP CSV header is missing OMM fields or has invalid columns"
        }
        val columns = header.withIndex().filter { it.value in CSV_OMM_FIELDS }
        var count = 0
        var rejected = 0
        val warnings = ArrayList<String>()
        while (true) {
            val row = readCsvRecord(reader) ?: break
            if (row.size == 1 && row[0].isBlank()) continue
            count++
            require(count <= MAX_INPUT_RECORDS) { "The file has more than $MAX_INPUT_RECORDS orbital records" }
            val record = try {
                require(row.size == header.size) { "Expected ${header.size} GP CSV columns, found ${row.size}" }
                val omm = JSONObject()
                for ((index, key) in columns) {
                    val value = row[index].trim()
                    if (value.isNotEmpty()) omm.put(key, value)
                }
                parseOmmObject(omm)
            } catch (error: Exception) {
                rejected++
                if (warnings.size < MAX_WARNINGS) {
                    warnings += "GP CSV record $count: ${error.message ?: error.javaClass.simpleName}".take(200)
                }
                null
            }
            if (record != null) accept(record)
        }
        require(count > 0) { "No GP CSV records found" }
        return OrbitParseReport(OrbitImportFormat.OMM_CSV, count, rejected, warnings)
    }

    /** Bounded RFC-4180 fields: commas, escaped quotes, and newlines inside quotes. */
    private fun readCsvRecord(reader: PushbackReader): List<String>? {
        val fields = ArrayList<String>()
        val value = StringBuilder()
        var quoted = false
        var closedQuote = false
        var started = false
        var chars = 0
        fun addField() {
            require(fields.size < MAX_CSV_COLUMNS) { "GP CSV has more than $MAX_CSV_COLUMNS columns" }
            fields += value.toString()
            value.setLength(0)
            closedQuote = false
        }
        while (true) {
            val code = reader.read()
            if (code == -1) {
                require(!quoted) { "Unterminated quoted GP CSV field" }
                if (!started) return null
                addField()
                return fields
            }
            started = true
            chars++
            require(chars <= MAX_CSV_RECORD_CHARS) { "A GP CSV record exceeds $MAX_CSV_RECORD_CHARS characters" }
            val char = code.toChar()
            if (quoted) {
                if (char == '"') {
                    val next = reader.read()
                    if (next == '"'.code) {
                        value.append('"')
                        chars++
                        require(chars <= MAX_CSV_RECORD_CHARS) { "A GP CSV record exceeds $MAX_CSV_RECORD_CHARS characters" }
                    } else {
                        quoted = false
                        closedQuote = true
                        if (next != -1) reader.unread(next)
                    }
                } else value.append(char)
                continue
            }
            when (char) {
                ',' -> addField()
                '\n' -> { addField(); return fields }
                '\r' -> {
                    val next = reader.read()
                    if (next != '\n'.code && next != -1) reader.unread(next)
                    addField()
                    return fields
                }
                '"' -> {
                    require(!closedQuote && value.isEmpty()) { "Unexpected quote in GP CSV field" }
                    quoted = true
                }
                else -> {
                    require(!closedQuote) { "Unexpected text after quoted GP CSV field" }
                    value.append(char)
                }
            }
        }
    }

    /** Normalizes OMM fields so SatelliteRecord.orbitElements can round-trip them. */
    internal fun parseOmmObject(json: JSONObject): SatelliteRecord {
        val fields = buildMap<String, Any?> {
            for (key in json.keys()) put(key, if (json.isNull(key)) null else json.get(key))
        }
        val elements = OmmElements.fromCelestrakFields(fields)
        val id = elements.noradId.trimStart('0')
        val name = json.optString("OBJECT_NAME").trim().take(120).ifEmpty { "NORAD $id" }
        val normalized = JSONObject(json.toString())
            .put("NORAD_CAT_ID", id)
            .put("EPOCH", elements.epoch.toString())
            .put("OBJECT_NAME", name)
        return SatelliteRecord(id, name, emptyList(), normalized, emptyList()).also {
            require(it.orbitElements() != null) { "The OMM record cannot be propagated" }
        }
    }

    private fun parseOmmJson(reader: PushbackReader, first: Int, accept: (SatelliteRecord) -> Unit): OrbitParseReport {
        var count = 0
        var rejected = 0
        val warnings = ArrayList<String>()
        fun addObject(firstChar: Int) {
            count++
            require(count <= MAX_INPUT_RECORDS) { "The file has more than $MAX_INPUT_RECORDS orbital records" }
            val objectText = readJsonObject(reader, firstChar)
            val record = try {
                parseOmmObject(JSONObject(objectText))
            } catch (error: Exception) {
                rejected++
                if (warnings.size < MAX_WARNINGS) {
                    warnings += "OMM record $count: ${error.message ?: error.javaClass.simpleName}".take(200)
                }
                null
            }
            if (record != null) accept(record)
        }
        if (first == '{'.code) {
            addObject(first)
        } else {
            var next = nextNonWhitespace(reader)
            if (next != ']'.code) {
                while (true) {
                    require(next == '{'.code) { "Expected an OMM object at record ${count + 1}" }
                    addObject(next)
                    next = nextNonWhitespace(reader)
                    if (next == ']'.code) break
                    require(next == ','.code) { "Expected a comma after OMM record $count" }
                    next = nextNonWhitespace(reader)
                    require(next != ']'.code) { "Trailing comma after OMM record $count" }
                }
            }
        }
        require(nextNonWhitespace(reader) == -1) { "Unexpected content after OMM JSON" }
        require(count > 0) { "No OMM records found" }
        return OrbitParseReport(OrbitImportFormat.OMM_JSON, count, rejected, warnings)
    }

    private fun readJsonObject(reader: PushbackReader, first: Int): String {
        require(first == '{'.code)
        val output = StringBuilder().append('{')
        var depth = 1
        var quoted = false
        var escaped = false
        while (depth > 0) {
            val code = reader.read()
            require(code != -1) { "Unterminated OMM JSON object" }
            val c = code.toChar()
            output.append(c)
            require(output.length <= MAX_JSON_OBJECT_CHARS) { "An OMM record exceeds $MAX_JSON_OBJECT_CHARS characters" }
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
        }
        return output.toString()
    }

    private fun parseTle(reader: PushbackReader, accept: (SatelliteRecord) -> Unit): OrbitParseReport {
        var count = 0
        var rejected = 0
        var lineNumber = 0
        var name: String? = null
        var line1: String? = null
        val warnings = ArrayList<String>()
        fun reject(message: String) {
            rejected++
            if (warnings.size < MAX_WARNINGS) warnings += message.take(200)
        }
        fun checkCount() {
            require(count <= MAX_INPUT_RECORDS) { "The file has more than $MAX_INPUT_RECORDS orbital records" }
        }
        while (true) {
            val line = readLine(reader) ?: break
            lineNumber++
            if (line.isBlank()) continue
            when {
                line.startsWith("1 ") -> {
                    if (line1 != null) {
                        count++
                        checkCount()
                        reject("TLE line $lineNumber: previous line 1 had no line 2")
                    }
                    line1 = line
                }
                line.startsWith("2 ") -> {
                    val first = line1
                    count++
                    checkCount()
                    if (first == null) reject("TLE line $lineNumber: line 2 has no line 1")
                    else {
                        val record = try {
                            parseTlePair(first, line, name)
                        } catch (error: Exception) {
                            reject("TLE line $lineNumber: ${error.message ?: error.javaClass.simpleName}")
                            null
                        }
                        if (record != null) accept(record)
                    }
                    line1 = null
                    name = null
                }
                else -> {
                    if (line1 != null) {
                        count++
                        checkCount()
                        reject("TLE line $lineNumber: line 1 had no line 2")
                        line1 = null
                    }
                    name = line.removePrefix("0 ").trim()
                }
            }
        }
        if (line1 != null) {
            count++
            checkCount()
            reject("TLE at end of file: line 1 had no line 2")
        }
        require(count > 0) { "No TLE pairs found" }
        return OrbitParseReport(OrbitImportFormat.TLE, count, rejected, warnings)
    }

    internal fun parseTlePair(first: String, second: String, rawName: String?): SatelliteRecord {
        require(first.length >= 69 && second.length >= 69) { "TLE lines must have at least 69 columns" }
        require(first[0] == '1' && first[1] == ' ' && second[0] == '2' && second[1] == ' ') {
            "TLE line numbers are invalid"
        }
        checkChecksum(first)
        checkChecksum(second)
        val id = parseTleCatalogNumber(first.substring(2, 7))
        require(id == parseTleCatalogNumber(second.substring(2, 7))) { "TLE satellite numbers differ" }
        val epochYear = first.substring(18, 20).toInt()
        val year = if (epochYear >= 57) 1900 + epochYear else 2000 + epochYear
        val dayText = first.substring(20, 32).trim()
        val dayOfYear = dayText.substringBefore('.').toInt()
        val start = LocalDate.of(year, 1, 1)
        require(dayOfYear in 1..start.lengthOfYear()) { "TLE epoch day is out of range" }
        val fraction = dayText.substringAfter('.', "")
        require(fraction.matches(Regex("[0-9]{1,8}"))) { "TLE epoch fraction is invalid" }
        val nanos = BigDecimal("0.$fraction").multiply(BigDecimal("86400000000000"))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        val epoch = start.plusDays(dayOfYear.toLong() - 1).atStartOfDay(ZoneOffset.UTC).toInstant().plusNanos(nanos)
        val objectId = parseInternationalDesignator(first.substring(9, 17))
        fun decimal(text: String, label: String): Double = text.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid $label")
        fun impliedDecimal(text: String, label: String): Double {
            require(text.length == 8 && (text[0] == ' ' || text[0] == '+' || text[0] == '-') &&
                text.substring(1, 6).all(Char::isDigit) && (text[6] == '+' || text[6] == '-') &&
                text[7].isDigit()) { "Invalid $label" }
            val sign = if (text[0] == '-') -1.0 else 1.0
            val exponent = text.substring(6, 8).toInt()
            return sign * text.substring(1, 6).toDouble() * 1e-5 * Math.pow(10.0, exponent.toDouble())
        }
        val eccentricityText = second.substring(26, 33)
        require(eccentricityText.all(Char::isDigit)) { "Invalid eccentricity" }
        val name = rawName?.trim()?.take(120)?.ifEmpty { null } ?: "NORAD $id"
        val omm = JSONObject()
            .put("OBJECT_NAME", name)
            .put("NORAD_CAT_ID", id)
            .put("EPOCH", epoch.toString())
            .put("MEAN_MOTION", decimal(second.substring(52, 63), "mean motion"))
            .put("ECCENTRICITY", "0.$eccentricityText".toDouble())
            .put("INCLINATION", decimal(second.substring(8, 16), "inclination"))
            .put("RA_OF_ASC_NODE", decimal(second.substring(17, 25), "right ascension"))
            .put("ARG_OF_PERICENTER", decimal(second.substring(34, 42), "argument of perigee"))
            .put("MEAN_ANOMALY", decimal(second.substring(43, 51), "mean anomaly"))
            .put("MEAN_MOTION_DOT", decimal(first.substring(33, 43), "mean motion derivative"))
            .put("MEAN_MOTION_DDOT", impliedDecimal(first.substring(44, 52), "second mean motion derivative"))
            .put("BSTAR", impliedDecimal(first.substring(53, 61), "B* drag term"))
            .put("CENTER_NAME", "EARTH")
            .put("REF_FRAME", "TEME")
            .put("TIME_SYSTEM", "UTC")
            .put("MEAN_ELEMENT_THEORY", "SGP4")
        if (objectId != null) omm.put("OBJECT_ID", objectId)
        return parseOmmObject(omm)
    }

    private fun parseInternationalDesignator(field: String): String? {
        val text = field.trim()
        val match = Regex("([0-9]{2})([0-9]{3})([A-Z]{1,3})").matchEntire(text) ?: return null
        val shortYear = match.groupValues[1].toInt()
        val year = if (shortYear >= 57) 1900 + shortYear else 2000 + shortYear
        return "$year-${match.groupValues[2]}${match.groupValues[3]}"
    }

    /** Space-Track Alpha-5 uses A-H,J-N,P-Z as prefixes for 100000-339999. */
    private fun parseTleCatalogNumber(field: String): String {
        require(field.length == 5)
        if (field[0] in 'A'..'Z') {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ"
            val prefix = alphabet.indexOf(field[0])
            require(prefix >= 0 && field.substring(1).all { it in '0'..'9' }) {
                "Invalid Alpha-5 TLE catalog number"
            }
            return ((prefix + 10) * 10_000 + field.substring(1).toInt()).toString()
        }
        require(field.all { it == ' ' || it in '0'..'9' }) { "Invalid TLE catalog number" }
        val id = field.trim().toIntOrNull() ?: throw IllegalArgumentException("Invalid TLE catalog number")
        require(id > 0) { "TLE catalog number is zero" }
        return id.toString()
    }

    private fun checkChecksum(line: String) {
        val expected = line[68].digitToIntOrNull() ?: throw IllegalArgumentException("TLE checksum is missing")
        val sum = line.substring(0, 68).sumOf { char -> when {
            char.isDigit() -> char.digitToInt()
            char == '-' -> 1
            else -> 0
        } }
        require(sum % 10 == expected) { "TLE checksum mismatch" }
    }

    private fun nextNonWhitespace(reader: PushbackReader): Int {
        while (true) {
            val code = reader.read()
            if (code == -1 || !code.toChar().isWhitespace() && code != 0xfeff) return code
        }
    }

    private fun readLine(reader: PushbackReader): String? {
        val text = StringBuilder()
        while (true) {
            val code = reader.read()
            if (code == -1) return if (text.isEmpty()) null else text.toString()
            if (code == '\n'.code) return text.toString()
            if (code == '\r'.code) {
                val next = reader.read()
                if (next != '\n'.code && next != -1) reader.unread(next)
                return text.toString()
            }
            text.append(code.toChar())
            require(text.length <= MAX_TLE_LINE_CHARS) { "A TLE line exceeds $MAX_TLE_LINE_CHARS characters" }
        }
    }

    private class LimitedInputStream(private val source: InputStream) : InputStream() {
        private var count = 0L
        override fun read(): Int {
            val value = source.read()
            if (value >= 0) {
                count++
                require(count <= MAX_INPUT_BYTES) { "The orbital data file exceeds $MAX_INPUT_BYTES bytes" }
            }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val value = source.read(bytes, offset, length)
            if (value > 0) {
                count += value
                require(count <= MAX_INPUT_BYTES) { "The orbital data file exceeds $MAX_INPUT_BYTES bytes" }
            }
            return value
        }
    }
}

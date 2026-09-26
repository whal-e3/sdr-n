package org.satelliteeavesdropper.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate

class OrbitImportRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun parsesTwoAndThreeLineTleWithValidatedChecksumAndEpoch() {
        val records = ArrayList<SatelliteRecord>()
        val report = OrbitImportParser.parse(
            "VANGUARD 1\n$LINE_1\n$LINE_2\n$LINE_1\n$LINE_2\n".byteInputStream(), records::add,
        )
        assertEquals(OrbitImportFormat.TLE, report.format)
        assertEquals(2, report.inputRecords)
        assertEquals(0, report.rejectedRecords)
        assertEquals("VANGUARD 1", records[0].name)
        assertEquals("NORAD 5", records[1].name)
        assertEquals("5", records[0].noradId)
        val orbit = requireNotNull(records[0].orbitElements())
        assertEquals(Instant.parse("2000-06-27T18:50:19.733568Z"), orbit.epoch)
        assertEquals(10.82419157, orbit.meanMotionRevolutionsPerDay, 1e-8)
        assertEquals(0.1859667, orbit.eccentricity, 1e-10)
        assertEquals(0.000028098, orbit.bStar, 1e-12)
        assertEquals("1958-002B", orbit.objectId)
        assertTrue(records.all { it.transmitters.isEmpty() })
    }

    @Test fun malformedTleIsSkippedWithoutLosingFollowingGoodPair() {
        val brokenChecksum = LINE_1.dropLast(1) + if (LINE_1.last() == '0') '1' else '0'
        val badEpoch = withTleChecksum(LINE_1.replaceRange(20, 23, "367"))
        val records = ArrayList<SatelliteRecord>()
        val report = OrbitImportParser.parse(
            "BAD CHECKSUM\n$brokenChecksum\n$LINE_2\nBAD EPOCH\n$badEpoch\n$LINE_2\nVALID\n$LINE_1\n$LINE_2\n"
                .byteInputStream(), records::add,
        )
        assertEquals(3, report.inputRecords)
        assertEquals(2, report.rejectedRecords)
        assertEquals(1, records.size)
        assertTrue(report.warnings.any { it.contains("checksum") })
        assertTrue(report.warnings.any { it.contains("epoch day") })
    }

    @Test fun alphaFiveAndSpacePaddedTleCatalogNumbersAreDecoded() {
        val alpha1 = withTleChecksum(LINE_1.replaceRange(2, 7, "E8493"))
        val alpha2 = withTleChecksum(LINE_2.replaceRange(2, 7, "E8493"))
        val padded1 = withTleChecksum(LINE_1.replaceRange(2, 7, "    5"))
        val padded2 = withTleChecksum(LINE_2.replaceRange(2, 7, "    5"))
        assertEquals("148493", OrbitImportParser.parseTlePair(alpha1, alpha2, "Alpha-5").noradId)
        assertEquals("5", OrbitImportParser.parseTlePair(padded1, padded2, null).noradId)
        val mismatched = withTleChecksum(LINE_2.replaceRange(2, 7, "A0000"))
        assertThrows(IllegalArgumentException::class.java) {
            OrbitImportParser.parseTlePair(alpha1, mismatched, null)
        }
    }

    @Test fun streamsOmmJsonAndPreservesNineDigitId() {
        val records = ArrayList<SatelliteRecord>()
        val json = "[${omm("123456789", "2026-09-23T00:12:34.123456", "NEW OBJECT")}," +
            "${omm("27386", "2026-09-23T00:12:34Z", "ENVISAT")}," +
            "{\"NORAD_CAT_ID\":0}]"
        val report = OrbitImportParser.parse(ByteArrayInputStream(json.toByteArray()), records::add)
        assertEquals(OrbitImportFormat.OMM_JSON, report.format)
        assertEquals(3, report.inputRecords)
        assertEquals(1, report.rejectedRecords)
        assertEquals("123456789", records.first().noradId)
        assertEquals(Instant.parse("2026-09-23T00:12:34.123456Z"), records.first().orbitElements()?.epoch)
        assertTrue(records.all { it.transmitters.isEmpty() })
    }

    @Test fun importsRawSpaceTrackGpJsonWithUtcSpaceEpochAndNumericStrings() = runBlocking {
        val raw = VALID_OMM.replace("2026-09-23T00:12:34Z", "2026-09-23 00:12:34")
            .replace("\"MEAN_MOTION\":14.3908367", "\"MEAN_MOTION\":\"14.3908367\"")
        val snapshot = OrbitImportRepository(temp.newFolder()).importStream("[$raw]".byteInputStream(), "gp.json")
        assertEquals(OrbitImportFormat.OMM_JSON, snapshot.summary.format)
        assertEquals(Instant.parse("2026-09-23T00:12:34Z"), snapshot.records.single().orbitElements()?.epoch)
        assertEquals("123456789", snapshot.records.single().noradId)
        assertTrue(snapshot.records.single().transmitters.isEmpty())
    }

    @Test fun gpCsvHandlesQuotedNamesNineDigitIdsAndRejectedRows() = runBlocking {
        val header = "OBJECT_NAME,NORAD_CAT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION," +
            "RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,BSTAR,UNUSED_METADATA"
        val row = "\"COSMOS, \"\"TEST\"\"\",123456789,2026-09-23 00:12:34,14.3908367," +
            "0.00012376,98.3936,216.0015,90.5489,323.9034,,ignored"
        val invalid = "BROKEN,0,2026-09-23 00:12:34,14.3908367,0.00012376," +
            "98.3936,216.0015,90.5489,323.9034,,ignored"
        val csv = "$header\r\n$row\r\n$invalid\r\n"
        val repo = OrbitImportRepository(temp.newFolder())
        val imported = repo.importStream(csv.byteInputStream(), "gp.CSV")
        assertEquals(OrbitImportFormat.OMM_CSV, imported.summary.format)
        assertEquals(2, imported.summary.inputRecords)
        assertEquals(1, imported.summary.rejectedRecords)
        assertEquals(1, imported.summary.savedRecords)
        assertEquals("COSMOS, \"TEST\"", imported.records.single().name)
        assertEquals("123456789", imported.records.single().noradId)
        assertEquals(Instant.parse("2026-09-23T00:12:34Z"), imported.records.single().orbitElements()?.epoch)
        assertTrue(imported.records.single().transmitters.isEmpty())
        assertEquals(OrbitImportFormat.OMM_CSV, requireNotNull(repo.load()).summary.format)
    }

    @Test fun malformedGpCsvHeaderLeavesPreviousImportIntact() = runBlocking {
        val repo = OrbitImportRepository(temp.newFolder())
        repo.importStream("[$VALID_OMM]".byteInputStream(), "good.json")
        val bad = "OBJECT_NAME,NORAD_CAT_ID,EPOCH\nBAD,5,2026-09-23T00:00:00Z\n"
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.importStream(bad.byteInputStream(), "bad.csv") }
        }
        assertTrue(error.message.orEmpty().contains("GP CSV header"))
        assertEquals("good.json", requireNotNull(repo.load()).summary.sourceName)
    }

    @Test fun gpCsvRetainsSourceReportedDecayDateAcrossPrivateCacheReload() = runBlocking {
        val csv = "OBJECT_NAME,NORAD_CAT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION," +
            "RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,DECAY_DATE\n" +
            "HISTORICAL,5,2026-09-20 00:00:00,14.0,0.001,51.6,10,20,30,2026-09-22\n"
        val repo = OrbitImportRepository(temp.newFolder())
        val imported = repo.importStream(csv.byteInputStream(), "history.csv")
        assertEquals(0, imported.summary.rejectedRecords)
        val reloaded = requireNotNull(repo.load()).records.single()
        assertEquals(LocalDate.of(2026, 9, 22), reloaded.recordedDecayDate)
        assertTrue(reloaded.isKnownDecayedAt(Instant.parse("2026-09-26T00:00:00Z")))
        assertTrue(reloaded.transmitters.isEmpty())
    }

    @Test fun singleLineDocumentsProviderJsonIsNotMistakenForTle() = runBlocking {
        val json = "[${omm("5", "2026-09-21T09:24:20.782080", "VANGUARD 1")}," +
            "${omm("27386", "2026-09-23T20:10:17.645664", "ENVISAT")}]"
        val singleLine = json.replace("\n", "")
        assertTrue(singleLine.length > 256)
        val bytes = singleLine.toByteArray(Charsets.UTF_8)
        val providerStyleStream = object : InputStream() {
            private var position = 0
            override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt() and 0xff
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position == bytes.size) return -1
                val count = minOf(length, 3, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
        }
        val snapshot = OrbitImportRepository(temp.newFolder()).importStream(providerStyleStream, "sample.json")
        assertEquals(OrbitImportFormat.OMM_JSON, snapshot.summary.format)
        assertEquals(2, snapshot.summary.savedRecords)
        assertEquals(0, snapshot.summary.rejectedRecords)
    }

    @Test fun jsonDocumentBomIsDecodedBeforeFormatSniffing() = runBlocking {
        val json = "[$VALID_OMM,$VALID_OMM]"
        val utf8 = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + json.toByteArray(Charsets.UTF_8)
        val utf16le = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + json.toByteArray(Charsets.UTF_16LE)
        val repo = OrbitImportRepository(temp.newFolder())
        assertEquals(OrbitImportFormat.OMM_JSON, repo.importStream(utf8.inputStream(), "utf8.json").summary.format)
        assertEquals(OrbitImportFormat.OMM_JSON, repo.importStream(utf16le.inputStream(), "utf16.json").summary.format)
    }

    @Test fun jsonFilenameReportsActualUnexpectedFirstCharacter() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            OrbitImportParser.parse("README text".byteInputStream(), OrbitImportFormat.OMM_JSON) { }
        }
        assertTrue(error.message.orEmpty().contains("first character U+0052"))
    }

    @Test fun importDeduplicatesByLatestEpochAndReloadsAtomicCache() = runBlocking {
        val importedAt = Instant.parse("2026-09-24T12:00:00Z")
        val directory = temp.newFolder()
        val repo = OrbitImportRepository(directory, Clock.fixed(importedAt, ZoneOffset.UTC))
        val older = omm("27386", "2026-09-20T00:00:00Z", "OLD")
        val newer = omm("27386", "2026-09-23T00:00:00Z", "NEW")
        val oldest = omm("27386", "2026-09-19T00:00:00Z", "OLDEST")
        val snapshot = repo.importStream("[$older,$newer,$oldest]".byteInputStream(), "../orbital.json")
        assertEquals(3, snapshot.summary.acceptedRecords)
        assertEquals(2, snapshot.summary.duplicateRecords)
        assertEquals(1, snapshot.summary.inFileReplacementRecords)
        assertEquals(1, snapshot.summary.addedRecords)
        assertEquals(0, snapshot.summary.replacedRecords)
        assertEquals(1, snapshot.summary.uniqueRecords)
        assertEquals(1, snapshot.summary.savedRecords)
        assertEquals("orbital.json", snapshot.summary.sourceName)
        assertTrue(snapshot.summary.provenance.contains("tracking only"))
        assertEquals("NEW", snapshot.records.single().name)

        val reloaded = requireNotNull(OrbitImportRepository(directory).load())
        assertEquals(snapshot.summary, reloaded.summary)
        assertEquals("NEW", reloaded.records.single().name)
        assertEquals(Instant.parse("2026-09-23T00:00:00Z"), reloaded.records.single().orbitElements()?.epoch)
        assertEquals("orbital.json", reloaded.sourceFor("27386")?.sourceName)
    }

    @Test fun multipleImportsAccumulateAndKeepNewestRecordProvenance() = runBlocking {
        val directory = temp.newFolder()
        val repo = OrbitImportRepository(directory)
        val first = "[${omm("27386", "2026-09-22T00:00:00Z", "OLD")}," +
            "${omm("123456789", "2026-09-23T00:00:00Z", "NINE DIGIT")}]"
        repo.importStream(first.byteInputStream(), "first.json")
        val second = "[${omm("27386", "2026-09-24T00:00:00Z", "NEW")}," +
            "${omm("123456789", "2026-09-20T00:00:00Z", "STALE")}," +
            "${omm("33333", "2026-09-23T00:00:00Z", "ADDED")}]"
        val imported = repo.importStream(second.byteInputStream(), "second.json")
        assertEquals(1, imported.summary.addedRecords)
        assertEquals(1, imported.summary.replacedRecords)
        assertEquals(3, imported.summary.savedRecords)
        assertEquals(2, imported.sources.size)
        assertEquals("NEW", imported.records.single { it.noradId == "27386" }.name)
        assertEquals("NINE DIGIT", imported.records.single { it.noradId == "123456789" }.name)
        assertEquals("first.json", imported.sourceFor("123456789")?.sourceName)
        assertEquals("second.json", imported.sourceFor("27386")?.sourceName)

        val reloaded = requireNotNull(OrbitImportRepository(directory).load())
        assertEquals(imported.summary, reloaded.summary)
        assertEquals(3, reloaded.records.size)
        assertEquals("first.json", reloaded.sourceFor("123456789")?.sourceName)
        assertEquals("second.json", reloaded.sourceFor("33333")?.sourceName)
        assertTrue(reloaded.records.all { it.transmitters.isEmpty() })
    }

    @Test fun invalidReplacementKeepsEarlierImport() = runBlocking {
        val directory = temp.newFolder()
        val repo = OrbitImportRepository(directory)
        val first = repo.importStream("[$VALID_OMM]".byteInputStream(), "good.json")
        assertEquals(1, first.records.size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.importStream("[{\"NORAD_CAT_ID\":0}]".byteInputStream(), "bad.json") }
        }
        val retained = requireNotNull(OrbitImportRepository(directory).load())
        assertEquals("good.json", retained.summary.sourceName)
        assertEquals("123456789", retained.records.single().noradId)
        assertTrue(retained.records.single().transmitters.isEmpty())
    }

    @Test fun oversizedTleLineFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            OrbitImportParser.parse(("X".repeat(257) + "\n$LINE_1\n$LINE_2\n").byteInputStream()) { }
        }
    }

    @Test fun importsAndReloadsThousandsOfOmmRecords() = runBlocking {
        val directory = temp.newFolder()
        val content = buildString {
            append('[')
            repeat(5_000) { index ->
                if (index > 0) append(',')
                append(omm((300_000 + index).toString(), "2026-09-23T00:00:00Z", "OBJECT $index"))
            }
            append(']')
        }
        val imported = OrbitImportRepository(directory).importStream(content.byteInputStream(), "bulk.json")
        assertEquals(5_000, imported.summary.savedRecords)
        assertEquals(0, imported.summary.rejectedRecords)
        val reloaded = requireNotNull(OrbitImportRepository(directory).load())
        assertEquals(5_000, reloaded.records.size)
        assertEquals("bulk.json", reloaded.sourceFor("304999")?.sourceName)
    }

    @Test fun importsCatalogSizedGpCsvWithoutChangingReceiveEligibility() = runBlocking {
        val count = 30_152
        val csv = buildString {
            append("OBJECT_NAME,NORAD_CAT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,")
            append("RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY\n")
            repeat(count) { index ->
                append("OBJECT ").append(index).append(',').append(400_000 + index)
                append(",2026-09-23 00:12:34,14.3908367,0.00012376,98.3936,216.0015,90.5489,323.9034\n")
            }
        }
        val repo = OrbitImportRepository(temp.newFolder())
        val snapshot = repo.importStream(csv.byteInputStream(), "full-gp.csv")
        assertEquals(count, snapshot.summary.inputRecords)
        assertEquals(count, snapshot.summary.savedRecords)
        assertEquals(0, snapshot.summary.rejectedRecords)
        assertEquals(OrbitImportFormat.OMM_CSV, snapshot.summary.format)
        assertTrue(snapshot.records.all { it.transmitters.isEmpty() })
        assertEquals(count, requireNotNull(repo.load()).records.size)
    }

    private fun omm(id: String, epoch: String, name: String): String = """{
        "OBJECT_NAME":"$name", "NORAD_CAT_ID":"$id", "EPOCH":"$epoch",
        "MEAN_MOTION":14.3908367, "ECCENTRICITY":0.00012376, "INCLINATION":98.3936,
        "RA_OF_ASC_NODE":216.0015, "ARG_OF_PERICENTER":90.5489, "MEAN_ANOMALY":323.9034,
        "BSTAR":2.8865136e-5, "CENTER_NAME":"EARTH", "REF_FRAME":"TEME",
        "TIME_SYSTEM":"UTC", "MEAN_ELEMENT_THEORY":"SGP4"
    }""".trimIndent()

    private fun withTleChecksum(line: String): String {
        val first68 = line.take(68)
        val sum = first68.sumOf { when {
            it.isDigit() -> it.digitToInt()
            it == '-' -> 1
            else -> 0
        } }
        return first68 + (sum % 10)
    }

    companion object {
        private const val LINE_1 = "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753"
        private const val LINE_2 = "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667"
        private const val VALID_OMM = """{"OBJECT_NAME":"NEW OBJECT","NORAD_CAT_ID":"123456789","EPOCH":"2026-09-23T00:12:34Z","MEAN_MOTION":14.3908367,"ECCENTRICITY":0.00012376,"INCLINATION":98.3936,"RA_OF_ASC_NODE":216.0015,"ARG_OF_PERICENTER":90.5489,"MEAN_ANOMALY":323.9034}"""
    }
}

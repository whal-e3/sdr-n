package org.satelliteeavesdropper.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.satelliteeavesdropper.app.data.OrbitImportFormat
import org.satelliteeavesdropper.app.data.OrbitImportRepository
import java.io.File

/** Exercises the Android ContentResolver stream used after the document picker returns a URI. */
@RunWith(AndroidJUnit4::class)
class OrbitImportDocumentInstrumentedTest {
    @Test fun singleLineOmmArrayThroughContentResolver() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "test-orbit-import").apply { mkdirs() }
        val sample = File(directory, "sample.json")
        val json = """[{"OBJECT_NAME":"VANGUARD 1","NORAD_CAT_ID":5,"EPOCH":"2026-09-21T09:24:20.782080","MEAN_MOTION":10.86032967,"ECCENTRICITY":0.1823493,"INCLINATION":34.2492,"RA_OF_ASC_NODE":38.7861,"ARG_OF_PERICENTER":293.8384,"MEAN_ANOMALY":49.8031},{"OBJECT_NAME":"ENVISAT","NORAD_CAT_ID":27386,"EPOCH":"2026-09-23T20:10:17.645664","MEAN_MOTION":14.3908367,"ECCENTRICITY":0.00012376,"INCLINATION":98.3936,"RA_OF_ASC_NODE":216.0015,"ARG_OF_PERICENTER":90.5489,"MEAN_ANOMALY":323.9034}]"""
        assertTrue(json.length > 256)
        sample.writeText(json)
        try {
            val result = OrbitImportRepository(File(directory, "cache"), contentResolver = context.contentResolver)
                .importFromUri(Uri.fromFile(sample))
            assertEquals(OrbitImportFormat.OMM_JSON, result.summary.format)
            assertEquals(2, result.summary.savedRecords)
            assertEquals(0, result.summary.rejectedRecords)
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test fun gpCsvThroughContentResolver() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "test-orbit-import-csv").apply { mkdirs() }
        val sample = File(directory, "gp.csv")
        sample.writeText(
            "OBJECT_NAME,NORAD_CAT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION," +
                "RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY\n" +
                "\"TEST, NINE DIGIT\",123456789,2026-09-23 00:12:34,14.3908367,0.00012376," +
                "98.3936,216.0015,90.5489,323.9034\n",
        )
        try {
            val result = OrbitImportRepository(File(directory, "cache"), contentResolver = context.contentResolver)
                .importFromUri(Uri.fromFile(sample))
            assertEquals(OrbitImportFormat.OMM_CSV, result.summary.format)
            assertEquals("123456789", result.records.single().noradId)
            assertEquals(1, result.summary.savedRecords)
            assertEquals(0, result.summary.rejectedRecords)
        } finally {
            directory.deleteRecursively()
        }
    }
}

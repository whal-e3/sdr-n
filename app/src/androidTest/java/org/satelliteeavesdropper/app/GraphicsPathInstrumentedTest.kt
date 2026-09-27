package org.satelliteeavesdropper.app

import android.graphics.Path
import androidx.graphics.path.PathIterator
import androidx.graphics.path.PathSegment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the rebuilt AndroidX JNI replacement, including curve conversion. */
@RunWith(AndroidJUnit4::class)
class GraphicsPathInstrumentedTest {
    @Test fun rebuiltLibraryLoadsAndIteratesCurves() {
        // Modern Android normally uses a framework iterator; force a library
        // load as well so a bad packaged binary cannot evade this check.
        System.loadLibrary("androidx.graphics.path")
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(3f, 4f)
            cubicTo(5f, 6f, 7f, 8f, 9f, 10f)
            close()
        }
        val segments = PathIterator(path).asSequence().toList()
        assertTrue(segments.any { it.type == PathSegment.Type.Line })
        assertTrue(segments.any { it.type == PathSegment.Type.Cubic })
        assertTrue(segments.flatMap { it.points.toList() }.all { it.x.isFinite() && it.y.isFinite() })
        val oval = Path().apply { addOval(0f, 0f, 100f, 80f, Path.Direction.CW) }
        val converted = PathIterator(oval, PathIterator.ConicEvaluation.AsQuadratics).asSequence().toList()
        assertTrue(converted.any { it.type == PathSegment.Type.Quadratic })
        assertTrue(converted.flatMap { it.points.toList() }.all { it.x.isFinite() && it.y.isFinite() })
    }
}

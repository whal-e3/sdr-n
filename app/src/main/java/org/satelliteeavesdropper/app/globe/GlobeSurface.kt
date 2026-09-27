package org.satelliteeavesdropper.app.globe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject
import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val TEXTURE_WIDTH = 1024
private const val TEXTURE_HEIGHT = 512

/** Rasterize bundled public-domain outlines once; never fetch a map or allocate a large Earth texture. */
internal fun loadGlobeSurface(context: Context): Bitmap {
    val bitmap = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(22, 56, 73))
        val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 96, 96) }
        val coastline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(91, 150, 146)
            style = Paint.Style.STROKE
            strokeWidth = 0.7f
        }
        val features = context.assets.open("maps/ne_110m_land.geojson").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("features")
        }
        for (index in 0 until features.length()) {
            val rings = features.getJSONObject(index).getJSONObject("geometry").getJSONArray("coordinates")
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            for (ringIndex in 0 until rings.length()) {
                val ring = rings.getJSONArray(ringIndex)
                for (pointIndex in 0 until ring.length()) {
                    val point = ring.getJSONArray(pointIndex)
                    val x = ((point.getDouble(0) + 180.0) / 360.0 * TEXTURE_WIDTH).toFloat()
                    val y = ((90.0 - point.getDouble(1)) / 180.0 * TEXTURE_HEIGHT).toFloat()
                    if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            canvas.drawPath(path, land)
            canvas.drawPath(path, coastline)
        }
        return bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}

/**
 * Tessellate the camera's front hemisphere, rather than clipping whole continent polygons.
 * All triangles stay inside the Earth disk, including at the poles and coastline limb.
 * Lighting is a fixed illustrative ambient light, not a predicted day/night terminator.
 */
internal object GlobeHemisphere {
    val standard by lazy { GlobeHemisphereMesh(columns = 32, rows = 24) }
    val detailed by lazy { GlobeHemisphereMesh(columns = 64, rows = 48) }
}

internal class GlobeHemisphereMesh(private val columns: Int, private val rows: Int) {
    private val gridPoints = (columns + 1) * (rows + 1)
    private val coordinates: DoubleArray
    private val triangleIndices: ShortArray
    val shades: IntArray

    init {
        coordinates = DoubleArray(gridPoints * 3)
        shades = IntArray(gridPoints * 2)
        for (index in 0 until gridPoints) {
            val row = index / (columns + 1)
            val column = index % (columns + 1)
            val latitude = (-90.0 + 180.0 * row / rows) * PI / 180.0
            val longitude = (-90.0 + 180.0 * column / columns) * PI / 180.0
            val x = cos(latitude) * sin(longitude)
            val y = sin(latitude)
            val z = max(0.0, cos(latitude) * cos(longitude))
            coordinates[index * 3] = x
            coordinates[index * 3 + 1] = y
            coordinates[index * 3 + 2] = z
            val light = max(0.0, -0.43 * x + 0.57 * y + 0.70 * z)
            val value = ((0.42 + 0.58 * light) * 255.0).toInt().coerceIn(0, 255)
            val shade = Color.rgb(value, value, value)
            shades[index] = shade
            shades[index + gridPoints] = shade
        }
        triangleIndices = ShortArray(columns * rows * 6)
        var offset = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val topLeft = row * (columns + 1) + column
                val bottomLeft = topLeft + columns + 1
                for (index in intArrayOf(topLeft, bottomLeft, topLeft + 1, topLeft + 1, bottomLeft, bottomLeft + 1)) {
                    triangleIndices[offset++] = index.toShort()
                }
            }
        }
    }

    fun screenVertices(projection: GlobeProjection): FloatArray = FloatArray(shades.size * 2).also { result ->
        val earthPixels = EARTH_RADIUS_KM * projection.pixelsPerKm
        for (index in 0 until gridPoints) {
            val x = (projection.centerX + coordinates[index * 3] * earthPixels).toFloat()
            val y = (projection.centerY - coordinates[index * 3 + 1] * earthPixels).toFloat()
            result[index * 2] = x
            result[index * 2 + 1] = y
            result[(index + gridPoints) * 2] = x
            result[(index + gridPoints) * 2 + 1] = y
        }
    }

    fun textureMesh(camera: GlobeCamera): GlobeTextureMesh {
        val result = FloatArray(shades.size * 2)
        for (index in 0 until gridPoints) {
            val x = coordinates[index * 3]
            val y = coordinates[index * 3 + 1]
            val z = coordinates[index * 3 + 2]
            val worldX = camera.earthFixedX(x, y, z)
            val worldY = camera.earthFixedY(x, y, z)
            val worldZ = camera.earthFixedZ(y, z)
            val longitude = atan2(worldY, worldX)
            val latitude = atan2(worldZ, sqrt(worldX * worldX + worldY * worldY))
            val u = ((longitude + PI) / (2.0 * PI) * TEXTURE_WIDTH).toFloat()
            val v = ((PI / 2.0 - latitude) / PI * TEXTURE_HEIGHT).toFloat()
            result[index * 2] = u
            result[index * 2 + 1] = v
            // A second variant is shared by triangles that cross the longitude seam.
            result[(index + gridPoints) * 2] = u + TEXTURE_WIDTH
            result[(index + gridPoints) * 2 + 1] = v
        }
        // Each triangle may cross the texture seam. REPEAT sampling keeps that small triangle
        // local to the dateline instead of stretching its texture across the whole planet.
        val indices = triangleIndices.copyOf()
        for (offset in indices.indices step 3) {
            val first = result[indices[offset].toInt() * 2]
            val second = result[indices[offset + 1].toInt() * 2]
            val third = result[indices[offset + 2].toInt() * 2]
            val maximum = maxOf(first, second, third)
            val minimum = minOf(first, second, third)
            if (maximum - minimum > TEXTURE_WIDTH / 2f) {
                for (vertex in 0..2) {
                    val index = indices[offset + vertex].toInt()
                    if (result[index * 2] < TEXTURE_WIDTH / 2f) indices[offset + vertex] = (index + gridPoints).toShort()
                }
            }
        }
        return GlobeTextureMesh(result, indices)
    }
}

internal data class GlobeTextureMesh(val textureCoordinates: FloatArray, val indices: ShortArray)

package com.micah.cj7brain.api

import android.R
import android.content.Context
import android.util.Log
import com.micah.cj7brain.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

const val TILE_SIZE = 256

fun fromLatLngToPoint(lat: Double, lng: Double): Map<String, Double> {
    val mercator = -ln(tan((0.25 + lat / 360.0) * Math.PI))
    val x = TILE_SIZE * (lng / 360.0 + 0.5)
    val y = TILE_SIZE / 2.0 * (1.0 + mercator / Math.PI)
    return mapOf("x" to x, "y" to y)
}

fun fromLatLngToTileCoord(lat: Double, lng: Double, zoom: Int): Map<String, Int> {
    val point = fromLatLngToPoint(lat, lng)
    val scale = 2.0.pow(zoom)
    val x = floor(point["x"]!! * scale / TILE_SIZE).toInt()
    val y = floor(point["y"]!! * scale / TILE_SIZE).toInt()
    return mapOf("x" to x, "y" to y, "z" to zoom)
}

object MapTilesApiClient {

    private val _sessionCreated = MutableStateFlow(false)
    val sessionCreated: StateFlow<Boolean> = _sessionCreated

    private lateinit var appContext: Context
    private lateinit var apiKey: String

    private val client = OkHttpClient()
    private var sessionJson: JSONObject? = null
    public var currentLat: Double = 0.0
    public var currentLong: Double = 0.0

    public var currentTileX: Int = 0
    public var currentTileY: Int = 0
    const val ZOOM_LEVEL: Int = 17
    private var testOffset = 0

    private val _currentTile = MutableStateFlow<ByteArray?>(null)
    val currentTile: StateFlow<ByteArray?> = _currentTile


    /** Must be called before using the API client */
    fun init(context: Context, apiKey: String) {
        this.appContext = context.applicationContext
        this.apiKey = apiKey
    }

    fun updateTile(tileBytes: ByteArray) {
        _currentTile.value = tileBytes
        emitTileData()
    }

    fun emitTileData() {
        if (_currentTile.value != null) {
            SocketManager.broadcastTileImage(currentTileX, currentTileY, ZOOM_LEVEL, _currentTile.value!!)
        }
    }

    fun getTile(x: Int, y: Int, zoom: Int) {
        Log.d("MapTilesApi", "Getting tile ($x, $y - z: $zoom) from api")
        CoroutineScope(Dispatchers.IO).launch {
            val newTile = fetchTile(x, y, zoom)
            newTile?.let {
                Log.d("MapTilesApi", "newTile retrieved. broadcasting tile")
                SocketManager.broadcastTileImage(x, y, zoom, newTile)
            }
        }
    }

    private fun checkTileChange() {
        if (!sessionCreated.value) return
//        testOffset += 1
//        if (testOffset % 7 != 0) return // HACK: only update tile every 10 test offset to not spam api

        val tileCoords = fromLatLngToTileCoord(currentLat, currentLong, ZOOM_LEVEL)
        val newX = tileCoords["x"]!! + testOffset
        val newY = tileCoords["y"]!!
        val xChanged = newX != currentTileX || currentTileX == 0
        val yChanged = newY != currentTileY || currentTileY == 0
        currentTileX = newX
        currentTileY = newY
        if (xChanged || yChanged) {
            Log.d("Tile", "Change occured")
            CoroutineScope(Dispatchers.IO).launch {
                val newTile = fetchTile(currentTileX, currentTileY, ZOOM_LEVEL)
                newTile?.let {
                    Log.d("Tile", "Fetched tile size: ${it.size} bytes")
                    // Optionally, update UI on main thread:
                    withContext(Dispatchers.Main) {
                        // update your ImageView/Bitmap here
                        Log.d("Tile", "tile changed setting tile for: ($currentTileX, $currentTileY)")
                        updateTile(newTile)
                    }
                }
            }
        }
    }

    /** ------------------------ SESSION HANDLING ------------------------ */

    fun setCoordinates(lat: Double, long: Double) {
        currentLat = lat
        currentLong = long
        checkTileChange()
    }

    suspend fun createSession(): JSONObject? = withContext(Dispatchers.IO) {
        val url = "https://tile.googleapis.com/v1/createSession?key=$apiKey"

        val jsonBody = """
            {
              "mapType": "roadmap",
              "language": "en-US",
              "region": "US",
              "styles": [
                { "featureType": "landscape", "elementType": "all", "stylers":[{"color":"#000000"}] },
                { "featureType": "landscape.man_made", "elementType": "geometry.stroke", "stylers":[{"color":"#41ff00"}] },
                { "featureType": "water", "stylers":[{"color":"#FF0000"}] },
                { "featureType": "road", "stylers":[{"color":"#41ff00"}] },
                { "featureType": "road", "elementType": "labels.text.fill", "stylers":[{"color":"#000000"}] }
              ]
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(MediaType.get("application/json"), jsonBody))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body()?.string()

            if (!response.isSuccessful || body == null) {
                Log.e("MapTilesApi", "Session error: ${response.code()} - ${response.message()} - ${response.body()} - ${response.toString()}")
                return@withContext null
            } else {
                Log.d("MapTilesApi", "Response successful for create session: ${response.body()}")
            }

            val json = JSONObject(body)
            sessionJson = json
            Log.d("MapTilesApi", "Session created: $json")
            _sessionCreated.value = sessionJson != null
            return@withContext json

        } catch (e: IOException) {
            Log.e("MapTilesApi", "Session exception: $e")
            return@withContext null
        }
    }

    private fun sessionKey(): String? {
        return sessionJson?.optString("session", null)
    }

    /** ------------------------ TILE FETCHING ------------------------ */

    suspend fun fetchTile(
        x: Int,
        y: Int,
        zoom: Int
    ): ByteArray? = withContext(Dispatchers.IO) {


        val session = sessionKey()
        if (session == null) {
            Log.e("MapTilesApi", "No session key. Call createSession() first.")
            return@withContext null
        }

        val url = "https://tile.googleapis.com/v1/2dtiles/$zoom/$x/$y?session=$session&key=$apiKey"
        Log.d("MapTilesApi", "Fetching tile: $url")

        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("MapTilesApi", "Failed tile ($x,$y): ${response.code()} - ${response.message()}")
                    return@withContext null
                }

                // Read raw bytes from response
                val bytes = response.body()?.bytes()
                if (bytes == null) {
                    Log.e("MapTilesApi", "Tile response body was null")
                    return@withContext null
                }

                Log.d("MapTilesApi", "Size: ${bytes.size}")

                return@withContext bytes
            }
        } catch (e: Exception) {
            Log.e("MapTilesApi", "Error fetching tile: $e")
            return@withContext null
        }
    }

}

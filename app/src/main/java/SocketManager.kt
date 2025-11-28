package com.micah.cj7brain

import android.graphics.Bitmap
import android.util.Log
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import android.util.Base64
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.navigation.RouteSegment
import com.micah.cj7brain.api.MapTilesApiClient
import org.json.JSONObject
import com.google.gson.Gson
import com.micah.cj7brain.api.NavigatorManager

data class LatLongDTO(
    val latitude: Double,
    val longitude: Double
)

object SocketManager {

    private var socket: Socket? = null
    private var spotifyAppRemote: SpotifyAppRemote? = null

    // Connection state
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    // Current track
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    // Player state
    private val _playerState = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = _playerState

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /** --- SocketIO --- */
    fun connect() {
        if (socket?.connected() == true) return

        val opts = IO.Options()
        socket = IO.socket("http://${BuildConfig.BACKEND_IP}:8089", opts)
//        socket = IO.socket("http://raspberrypi.local:5000", opts)

        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("SocketIO", "Connected")
            _connectionState.value = true
            socket?.emit("android_connect")
            onConnected?.invoke()
        }

        socket?.on(Socket.EVENT_DISCONNECT) {
            Log.d("SocketIO", "Disconnected")
            _connectionState.value = false
            onDisconnected?.invoke()
        }
        socket?.on("phone_skip_song") {
            spotifyAppRemote?.playerApi?.skipNext()
        }

        socket?.on("android_reload_page") {
            NavigatorManager.handlePageReload()
        }

        socket?.on("android_request_tile") { args ->
            if (args.isNotEmpty()) {
                val json = args[0] as? JSONObject
                json?.let {
                    val x = it.optInt("x", 0)
                    val y = it.optInt("y", 0)
                    val zoom = it.optInt("zoom", 0)

                    Log.d("SocketIO", "Getting tile at x=$x, y=$y, zoom=$zoom")
                    MapTilesApiClient.getTile(x, y, zoom)
                } ?: Log.w("SocketIO", "Tile request payload is null or not a JSONObject")
            }
        }

        Log.d("SocketIO", "Connecting...")
        socket?.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        _connectionState.value = false
    }

    fun broadcastTimeAndDistance(meters: Int, seconds: Int) {
        Log.d("SocketIO", "broadcasting time and distance: meters -${meters} - seconds: ${seconds}")
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("time_and_distance", JSONObject().apply {
                    put("meters", meters)
                    put("seconds", seconds)
                })            }
        }
    }

    fun broadcastTurnByTurnEvent(road: String?, maneuver: Int?, instruct: String?, side: Int?, meters: Int?, seconds: Int?, step: Int?, exit: String?) {
        Log.d("SocketIO", "Emitting turn by turn event.")
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("turn_by_turn", JSONObject().apply {
                    put("road", road)
                    put("maneuver", maneuver)
                    put("side", side)
                    put("meters", meters)
                    put("seconds", seconds)
                    put("step", step)
                    put("exit", exit)
                })
            }
        }
    }

    fun broadcastRouteSegments(segments: List<RouteSegment>) {
        scope.launch {
            if (socket?.connected() == true) {

                val segmentList: List<List<LatLongDTO>> = segments.map { segment: RouteSegment ->

                    segment.latLngs.map { latLng: LatLng ->
                        LatLongDTO(latLng.latitude, latLng.longitude)
                    }
                }

                // Serialize list-of-lists to JSON
                val jsonPayload = Gson().toJson(segmentList)

                // Emit through socket.io
                Log.d("SocketIO", "emitting route segments")
                socket?.emit("route_segments", jsonPayload)
            }
        }
    }

    fun updateSpotifyState(playerState: PlayerState) {
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("song_change", playerState)
            }
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    fun updateAlbumArt(image: Bitmap) {
        scope.launch {
            if (socket?.connected() == true) {
                val bitmapData = bitmapToBase64(image)
                socket?.emit("album_image", bitmapData)
            }
        }
    }

    fun updateLocation(lat: Double, long: Double, bearing: Float, speed: Float) {
        socket?.emit("location_update", JSONObject().apply {
            put("lat", lat)
            put("long", long)
            put("bearing", bearing)
            put("speed", speed)
        })
    }

    fun broadcastTileImage(x: Int, y: Int, zoom: Int, tileImage: ByteArray) {
        Log.d("SocketIO", "Broadcasting tile image - ($x,$y) zoom: $zoom")
        val base64 = Base64.encodeToString(tileImage, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("x", x)
            put("y", y)
            put("zoom", zoom)
            put("image", base64)
        }
        socket?.emit("tile_data", payload)
    }

    /** --- Spotify --- */
    fun setSpotifyAppRemote(appRemote: SpotifyAppRemote) {
        spotifyAppRemote = appRemote
        subscribeToPlayerState()
    }

    private fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            _playerState.value = playerState
            val track = playerState.track
            if (track != null) {
                _currentTrack.value = track
                updateSpotifyState(playerState)
            }
        }
    }

    fun playSong(playlistUri: String = "spotify:playlist:37i9dQZF1DX2sUQwD7tbmL") {
        spotifyAppRemote?.playerApi?.play(playlistUri)
    }

    fun playPause() {
        val player = spotifyAppRemote ?: return
        val state = _playerState.value ?: return

        if (state.isPaused) {
            player.playerApi.resume()
        } else {
            player.playerApi.pause()
        }
    }
}

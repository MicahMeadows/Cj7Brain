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
import com.spotify.protocol.client.Subscription

data class LatLongDTO(
    val latitude: Double,
    val longitude: Double
)

object SocketManager {

    private var lastBatteryLevel: Int = 0
    private var socket: Socket? = null
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var playerStateSub: Subscription<PlayerState>? = null
    private var lastAlbumImage: String? = null

    // Connection state
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    // Current track
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    // Player state
    private val _playerState = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = _playerState

    private var lastPlayerStateJson: JSONObject? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    // Raise (true) / lower (false) system media volume — wired to AudioManager
    // by AppLogicService, which owns the AudioManager instance.
    var onVolumeChange: ((up: Boolean) -> Unit)? = null

    private var lastVolume = 0

    private val scope = CoroutineScope(Dispatchers.IO)


    fun setRaspberryPiIp(ip: String) {
        connect(ip);
    }

    /** --- SocketIO --- */
    fun connect(ip: String) {
        if (socket?.connected() == true) return

        val opts = IO.Options()
//        socket = IO.socket("http://${BuildConfig.BACKEND_IP}:8089", opts)
        socket = IO.socket("http://${ip}:8089", opts)


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
        // Media control events broadcast by the server — triggered by the web UI
        // buttons or the Pi's physical GPIO buttons. Drive Spotify / system volume.
        socket?.on("skip_song") {
            spotifyAppRemote?.playerApi?.skipNext()
        }

        socket?.on("previous_song") {
            spotifyAppRemote?.playerApi?.skipPrevious()
        }

        socket?.on("play_pause") {
            playPause()
        }

        socket?.on("volume_up") {
            onVolumeChange?.invoke(true)
        }

        socket?.on("volume_down") {
            onVolumeChange?.invoke(false)
        }

        // Search & navigate: search for the given query, take the nearest
        // result, and start directions immediately (e.g. the Taco Bell button).
        socket?.on("search_and_navigate") { args ->
            val query = (args.getOrNull(0) as? JSONObject)?.optString("query")
            Log.d("SocketIO", "Received search_and_navigate: query=$query")
            if (!query.isNullOrBlank()) {
                PlaceSearchNavigator.searchAndNavigate(query)
            }
        }

        socket?.on("android_reload_page") {
            NavigatorManager.handlePageReload()
            emitLastAlbumImage()
            spotifyAppRemote?.playerApi?.playerState?.setResultCallback { playerState ->
                onNewPlayerState(playerState)
            }
            emitBatteryLevel(lastBatteryLevel)
            emitVolume()
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

    fun volumeChanged(volume: Int) {
        lastVolume = volume
        emitVolume()
    }

    private fun emitVolume() {
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("volume_change", lastVolume)
            }
        }
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

    fun emitBatteryLevel(batteryLevel: Int) {
        lastBatteryLevel = batteryLevel
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("battery_level", batteryLevel)
            }
        }
    }

    fun emitRouteEnd() {
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("end_route", {})
            }
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

    private fun emitPlayerStateJson(playerStateJson: JSONObject) {
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("song_change", playerStateJson)
            }
        }
    }

    fun updateSpotifyState(playerState: PlayerState) {
        Log.d("Spotify", "Song changed emitting new song data")
        val playerStateJson = JSONObject().apply {

            put("track", JSONObject().apply {

                put("name", playerState.track.name)
                put("uri", playerState.track.uri)
                put("duration", playerState.track.duration)

                put("isEpisode", playerState.track.isEpisode)
                put("isPodcast", playerState.track.isPodcast)

                put("artist", JSONObject().apply {
                    put("name", playerState.track.artist.name)
                    put("uri", playerState.track.artist.uri)
                })

                put("artists", playerState.track.artists.map { artist ->
                    JSONObject().apply {
                        put("name", artist.name)
                        put("uri", artist.uri)
                    }
                })

                put("album", JSONObject().apply {
                    put("name", playerState.track.album.name)
                    put("uri", playerState.track.album.uri)
                })
            })

            put("isPaused", playerState.isPaused)
            put("playbackSpeed", playerState.playbackSpeed)
            put("playbackPosition", playerState.playbackPosition)

            put("playbackOptions", JSONObject().apply {
                put("isShuffling", playerState.playbackOptions.isShuffling)
                put("repeatMode", playerState.playbackOptions.repeatMode)
            })

            put("playbackRestrictions", JSONObject().apply {
                put("canSkipNext", playerState.playbackRestrictions.canSkipNext)
                put("canSkipPrev", playerState.playbackRestrictions.canSkipPrev)
                put("canRepeatTrack", playerState.playbackRestrictions.canRepeatTrack)
                put("canRepeatContext", playerState.playbackRestrictions.canRepeatContext)
                put("canToggleShuffle", playerState.playbackRestrictions.canToggleShuffle)
            })
        }

        lastPlayerStateJson = playerStateJson

        emitPlayerStateJson(playerStateJson)
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    fun updateAlbumArt(image: Bitmap) {
        val bitmapData = bitmapToBase64(image)
        lastAlbumImage = bitmapData
        emitAlbumImage(bitmapData)

    }

    fun emitLastAlbumImage() {
        if (lastAlbumImage == null) {
            Log.d("SocketIO", "No last album image to reload to")
            return
        }
        emitAlbumImage(lastAlbumImage!!)
    }

    private fun emitAlbumImage(imageData: String) {
        scope.launch {
            if (socket?.connected() == true) {
                socket?.emit("album_image", imageData)
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
    fun setSpotifyAppRemote(appRemote: SpotifyAppRemote?) {
        spotifyAppRemote = appRemote
        subscribeToPlayerState()
    }

    fun onNewPlayerState(playerState: PlayerState) {
        _playerState.value = playerState
        val track = playerState.track
        if (track != null) {
            _currentTrack.value = track
            updateSpotifyState(playerState)
            spotifyAppRemote?.imagesApi?.getImage(track.imageUri, Image.Dimension.X_SMALL)?.setResultCallback { bitmap ->
                updateAlbumArt(bitmap)
            }
        }
    }

    fun unsubscribePlayerState() {
        _currentTrack.value = null
        try {
            playerStateSub?.cancel()
        } catch (ex: Error) {
            Log.w("Socket", "failed to unsubscribe player state")
        }
        playerStateSub = null

    }

    private fun subscribeToPlayerState() {
        Log.d("Spotify", "subbing to player state")
        if (playerStateSub != null) {
            Log.d("Spotify", "Already subscribed to player state not sub again")
            return
        }
        playerStateSub = spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            onNewPlayerState(playerState)
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

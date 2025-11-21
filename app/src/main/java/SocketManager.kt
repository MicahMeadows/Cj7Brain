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
//        socket = IO.socket("http://192.168.44.162:5000", opts)
        socket = IO.socket("http://raspberrypi.local:5000", opts)


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

        Log.d("SocketIO", "Connecting...")
        socket?.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        _connectionState.value = false
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

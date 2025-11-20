package com.micah.cj7brain

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.micah.cj7brain.ui.theme.Cj7BrainTheme
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import java.net.Socket


class MainActivity : ComponentActivity() {
    private val clientId = "60c84d324a05431ca667d118f68a9cfb"
    private val redirectUri = "your.app://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var canPlay = false
    // private var playingSong by mutableStateOf("None")
    private var playingTrack: Track? by mutableStateOf(null)
    private var curPlayerState: PlayerState? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Cj7BrainTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center

                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SocketStatusUI()
                            Text("Song Playing: ${playingTrack?.name}")
                            Button(onClick = {
                                if (canPlay) {
                                    playSong()
                                }
                            }) {
                                Text("Play Song")
                            }
                            Button(onClick = {
                                playPause()
                            }) {
                                if (curPlayerState?.isPaused == true) {
                                    Text("Resume")
                                } else {
                                    Text("Pause")
                                }
                            }
//                            Image(
//                                painter = painterResource(id = R.drawable.road_img_background),
//                                contentDescription = "road_img_test",
//                                modifier = Modifier.fillMaxSize(),
//                                contentScale = ContentScale.Crop
//                            )
                        }

                    }
                }
            }
        }
    }

    @Composable
    fun SocketStatusUI() {
        val connected by SocketManager.connectionState.collectAsState();
        val status = if (connected) "Connected" else "Disconnected"

        Text(
            text = status,
            style = MaterialTheme.typography.headlineSmall
        )
    }

    override fun onStart() {
        super.onStart()

        SocketManager.connect()

        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("MainActivity", "Connected! Yay!")
                // Now you can start interacting with App Remote
                connected()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("MainActivity", throwable.message, throwable)
                // Something went wrong when attempting to connect! Handle errors here
            }
        })
    }

    private fun playPause() {
        spotifyAppRemote?.let {
            if (curPlayerState?.isPaused == true) {
                Log.i("MainActivity", "resume")
                spotifyAppRemote?.playerApi?.resume()
            } else {
                Log.i("MainActivity", "pause")
                spotifyAppRemote?.playerApi?.pause()
            }
        }
    }
    private fun playSong() {
        spotifyAppRemote?.let {
            // Play a playlist
            val playlistURI = "spotify:playlist:37i9dQZF1DX2sUQwD7tbmL"
            it.playerApi.play(playlistURI)
            // Subscribe to PlayerState
            it.playerApi.subscribeToPlayerState().setEventCallback { playerState ->
                curPlayerState = playerState
                val track: Track = playerState.track
                Log.d("MainActivity", track.name + " by " + track.artist.name)
                playingTrack = track
            }
        }
    }

    private fun connected() {
        canPlay = true
    }

    override fun onStop() {
        super.onStop()

        SocketManager.disconnect()

        canPlay = false
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}
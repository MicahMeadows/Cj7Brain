package com.micah.cj7brain

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
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
import com.micah.cj7brain.ui.theme.Cj7BrainTheme
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track


class MainActivity : ComponentActivity() {

    // Track info and player state will come from the service via SocketManager or another shared flow
    private var playingTrack: Track? by mutableStateOf(null)
    private var curPlayerState: PlayerState? by mutableStateOf(null)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start your foreground service to handle Spotify + SocketIO in background
        val serviceIntent = Intent(this, AppLogicService::class.java)
        startForegroundService(serviceIntent)

        setContent {
            Cj7BrainTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SocketStatusUI()

                            // Update the currently playing track info from shared state
                            val currentTrack by SocketManager.currentTrack.collectAsState(initial = null)
                            val currentPlayerState by SocketManager.playerState.collectAsState(initial = null)

                            Text("Song Playing: ${currentTrack?.name ?: "None"}")

                            Button(onClick = { SocketManager.playSong() }) {
                                Text("Play Song")
                            }

                            Button(onClick = {
                                SocketManager.playPause()
                            }) {
                                if (currentPlayerState?.isPaused == true) Text("Resume") else Text("Pause")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SocketStatusUI() {
        val connected by SocketManager.connectionState.collectAsState()
        val status = if (connected) "Connected" else "Disconnected"
        Text(text = status, style = MaterialTheme.typography.headlineSmall)
    }
}
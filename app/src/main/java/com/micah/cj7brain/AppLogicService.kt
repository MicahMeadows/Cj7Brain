package com.micah.cj7brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
class AppLogicService : Service() {

    private val clientId = "60c84d324a05431ca667d118f68a9cfb"
    private val redirectUri = "your.app://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startSocket()
        connectSpotify()
    }

    private fun startForegroundService() {
        val channelId = "app_logic_service_channel"
        val channelName = "App Logic Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("App Running in Background")
            .setContentText("Handling Spotify and socket events")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)
    }

    private fun startSocket() {
        // Start your SocketManager here
        SocketManager.connect()
        Log.d("AppLogicService", "Socket connected")
    }

    private fun connectSpotify() {
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                Log.d("AppLogic", "Spotify app remote connected!")
                spotifyAppRemote = appRemote
                SocketManager.setSpotifyAppRemote(appRemote)
                subscribeToPlayer()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("AppLogicService", "Spotify connection failed", throwable)
            }
        })
    }

    private fun subscribeToPlayer() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            val track: Track? = playerState.track
            if (track != null) {
                Log.d("AppLogicService", "Playing: ${track.name} by ${track.artist.name}")

                SocketManager.updateSpotifyState(playerState)

                spotifyAppRemote?.imagesApi?.getImage(track.imageUri, Image.Dimension.X_SMALL)?.setResultCallback { bitmap ->
                    SocketManager.updateAlbumArt(bitmap)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        SocketManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
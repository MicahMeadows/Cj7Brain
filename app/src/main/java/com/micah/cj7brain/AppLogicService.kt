package com.micah.cj7brain

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import android.os.*
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.micah.cj7brain.api.MapTilesApiClient
import com.micah.cj7brain.api.MapTilesApiClient.createSession
import com.micah.cj7brain.api.NavigatorManager
import com.micah.cj7brain.api.fromLatLngToTileCoord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class AppLogicService : Service() {

    private val clientId = "60c84d324a05431ca667d118f68a9cfb"
    private val redirectUri = "your.app://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private val spotifyReconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            // Always try to connect if null or disconnected
            Log.d("AppLogic", "reconnect? : ${if (spotifyAppRemote == null) "null" else "not null"}")

            if (spotifyAppRemote == null) {
                Log.d("AppLogicService", "SpotifyAppRemote is null or disconnected. Attempting reconnect...")
                connectSpotify()
            }
            // Schedule next attempt in 1 minute, always
            spotifyReconnectHandler.postDelayed(this, 60_000)
        }
    }


    private fun setupMapTileApiClient() {
        MapTilesApiClient.init(this, BuildConfig.API_KEY)

        CoroutineScope(Dispatchers.IO).launch() {
            val session = createSession()
            if (session == null) {
                Log.e("MapTilesApi", "Couldn't create map tile session")
            } else {
                Log.d("MapTilesApi", "Created map tile session")

               // val tile = MapTilesApiClient.fetchTile(0,0,0)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startSocket()
        connectSpotify()
        spotifyReconnectHandler.postDelayed(reconnectRunnable, 10_000)

        NavigatorManager.initializeNavigationApi(this)

        setupMapTileApiClient()

        NavigatorManager.setupTurnByTurnThread()
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



    override fun onBind(intent: Intent?): IBinder? {
//        return NavigatorManager.getIncomingMessenger().binder
        return NavigatorManager.incomingMessenger.binder
    }

    private fun startSocket() {
        // Start your SocketManager here
        SocketManager.connect()
        Log.d("AppLogicService", "Socket connected")
    }

    private fun connectSpotify() {
        if (spotifyAppRemote != null) {
            try {
                SpotifyAppRemote.disconnect(spotifyAppRemote)
            } catch (_: Exception) {}
            spotifyAppRemote = null
        }

        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                Log.d("AppLogic", "Spotify app remote connected!")
                spotifyAppRemote = appRemote
                SocketManager.setSpotifyAppRemote(appRemote)

                spotifyReconnectHandler.removeCallbacks(reconnectRunnable)
            }

            override fun onFailure(throwable: Throwable) {
                spotifyAppRemote = null
                SocketManager.unsubscribePlayerState()
                spotifyReconnectHandler.postDelayed(reconnectRunnable, 60_000)
                Log.e("AppLogicService", "Spotify connection failed", throwable)
            }
        })
    }


    override fun onDestroy() {
        super.onDestroy()
        spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyReconnectHandler.removeCallbacks(reconnectRunnable)
        SocketManager.disconnect()
    }

    // override fun onBind(intent: Intent?): IBinder? = null
}
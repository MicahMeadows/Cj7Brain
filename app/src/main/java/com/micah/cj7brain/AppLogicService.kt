package com.micah.cj7brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.micah.cj7brain.api.MapTilesApiClient
import com.micah.cj7brain.api.MapTilesApiClient.createSession
import com.micah.cj7brain.api.fromLatLngToTileCoord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppLogicService : Service() {

    private val clientId = "60c84d324a05431ca667d118f68a9cfb"
    private val redirectUri = "your.app://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private lateinit var incomingMessenger: Messenger
    private lateinit var turnByTurnManager: TurnByTurnManager

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

        val mRoadSnappedLocationProvider = NavigationApi.getRoadSnappedLocationProvider(application)

        setupMapTileApiClient()

        mRoadSnappedLocationProvider?.addLocationListener(object : RoadSnappedLocationProvider.LocationListener {
            override fun onLocationChanged(newLocation: Location?) {
                Log.d("Location", "New location update: ${newLocation.toString()}")
                // TODO: update new location update to socketio
                newLocation?.let {
                    SocketManager.updateLocation(newLocation.latitude, newLocation.longitude)
                }
            }

            override fun onRawLocationUpdate(newLocation: Location) {
                Log.d("Location", "Raw location update: ${newLocation.toString()}")
                // TODO: update raw location update to socketio
                // MapTilesApiClient.setCoordinates(newLocation.latitude, newLocation.longitude)
            }
        })

        turnByTurnManager = TurnByTurnManager.createInstance()
        val thread = HandlerThread(
            "NavInfoReceivingService",
            Process.THREAD_PRIORITY_DEFAULT

        )
        thread.start()
        incomingMessenger = Messenger(IncomingNavStepHandler(thread.looper))
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

    private inner class IncomingNavStepHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
                val navInfo: NavInfo? = turnByTurnManager.readNavInfoFromBundle(msg.data)
                Log.d("TurnByTurn", "road: ${navInfo?.currentStep?.fullRoadName}")
                Log.d("TurnByTurn", "manuever: ${navInfo?.currentStep?.maneuver}")
                Log.d("TurnByTurn", "instruct: ${navInfo?.currentStep?.fullInstructionText}")
                Log.d("TurnByTurn", "side: ${navInfo?.currentStep?.drivingSide}")
                Log.d("TurnByTurn", "dist: ${navInfo?.currentStep?.distanceFromPrevStepMeters}")
                Log.d("TurnByTurn", "time: ${navInfo?.currentStep?.timeFromPrevStepSeconds}")
                Log.d("TurnByTurn", "step: ${navInfo?.currentStep?.stepNumber}")
                Log.d("TurnByTurn", "exit: ${navInfo?.currentStep?.exitNumber}")
                Log.d("TurnByTurn", "=================================================")

                // TODO: from here we cant send this turn by turn details over the socketio connection so the
                // backend can receive and send it to the frontend. this will happen like every second



                // Do something with navInfo
            } else {
                super.handleMessage(msg)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return incomingMessenger.binder
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

    // override fun onBind(intent: Intent?): IBinder? = null
}
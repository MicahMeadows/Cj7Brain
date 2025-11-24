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

import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import com.google.android.libraries.navigation.SupportNavigationFragment

import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.TermsAndConditionsCheckOption

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import com.google.android.libraries.navigation.NavigationApi.*
import com.google.android.libraries.navigation.Navigator

class MainActivity : FragmentActivity() {
    // Track info and player state will come from the service via SocketManager or another shared flow
    private var playingTrack: Track? by mutableStateOf(null)
    private var curPlayerState: PlayerState? by mutableStateOf(null)

    private val locationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (!granted) {
            // Handle permission denied case
            Log.d("Location", "Location permission no")

        } else {
            Log.d("Location", "Location permission yes")
        }
    }

    private fun checkPermissionGranted(permissionToCheck: String): Boolean =
        ContextCompat.checkSelfPermission(this, permissionToCheck) == PackageManager.PERMISSION_GRANTED

    private fun showToast(errorMessage: String) {
        Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
    }

    /** Starts the Navigation API, capturing a reference when ready. */
    @SuppressLint("MissingPermission")
    private fun initializeNavigationApi() {
        getNavigator(
            this,
            object : NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    // store a reference to the Navigator object
                    val mNavigator = navigator
                    // code to start guidance will go here
                }

                override fun onError(@ErrorCode errorCode: Int) {
                    when (errorCode) {
                        ErrorCode.NOT_AUTHORIZED -> {
                            // Note: If this message is displayed, you may need to check that
                            // your API_KEY is specified correctly in AndroidManifest.xml
                            // and is been enabled to access the Navigation API
                            showToast(
                                "Error loading Navigation API: Your API key is " +
                                        "invalid or not authorized to use Navigation."
                            )
                        }
                        ErrorCode.TERMS_NOT_ACCEPTED -> {
                            showToast(
                                "Error loading Navigation API: User did not " +
                                        "accept the Navigation Terms of Use."
                            )
                        }
                        else -> showToast("Error loading Navigation API: $errorCode")
                    }
                }
            },
        )

    }



    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start your foreground service to handle Spotify + SocketIO in background
        val serviceIntent = Intent(this, AppLogicService::class.java)
        startForegroundService(serviceIntent)

        requestLocationPermissions()

        initializeNavigationApi()

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

                            NavigationFragmentHost()
                        }
                    }
                }
            }
        }
    }

    private fun requestLocationPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fineLocation != PackageManager.PERMISSION_GRANTED &&
            coarseLocation != PackageManager.PERMISSION_GRANTED) {
            locationPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @Composable
    fun SocketStatusUI() {
        val connected by SocketManager.connectionState.collectAsState()
        val status = if (connected) "Connected" else "Disconnected"
        Text(text = status, style = MaterialTheme.typography.headlineSmall)
    }

    @Composable
    fun NavigationFragmentHost() {
        AndroidView(factory = { context ->
            val container = androidx.fragment.app.FragmentContainerView(context).apply {
                id = android.view.View.generateViewId()
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            if (supportFragmentManager.findFragmentById(container.id) == null) {
                val fragment = SupportNavigationFragment()
                supportFragmentManager.commit {
                    setReorderingAllowed(true)
                    add(container.id, fragment)
                }
            }

            container
        })
    }


}
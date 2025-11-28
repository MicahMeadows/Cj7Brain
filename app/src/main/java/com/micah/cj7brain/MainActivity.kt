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
import android.R
import android.annotation.SuppressLint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.libraries.navigation.NavigationApi.*
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.Job

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.ListenableResultFuture
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.jvm.java

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.micah.cj7brain.api.MapTilesApiClient
import com.micah.cj7brain.api.NavigatorManager
import com.micah.cj7brain.api.fromLatLngToTileCoord
import kotlinx.coroutines.flow.map

class MainActivity : FragmentActivity() {

    private var placesClient: PlacesClient? = null

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

    private fun initializePlacesApi() {
        Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.API_KEY)
        placesClient = Places.createClient(this)
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val serviceIntent = Intent(this, AppLogicService::class.java)
        startForegroundService(serviceIntent)

        requestLocationPermissions()

        initializePlacesApi()

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

//                            Button(onClick = { SocketManager.playSong() }) {
//                                Text("Play Song")
//                            }
//
//                            Button(onClick = {
//                                SocketManager.playPause()
//                            }) {
//                                if (currentPlayerState?.isPaused == true) Text("Resume") else Text("Pause")
//                            }

                            placesClient?.let { client ->
                                PlacesSearchField(
                                    placesClient = client,
                                    onPlaceSelected = { placeId ->
                                        NavigatorManager.prepareRoute(placeId) // prepares route immediately
                                    },
                                    onStartNavigation = {
                                        NavigatorManager.pendingPlaceId?.let { placeId ->
                                            // Actually start guidance now
                                            NavigatorManager.startNavigation()
                                            Log.d("Navigation", "Google maps navigation started!")
                                        }
                                    }
                                )
                            }

                            MapTileImage()

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
        val status = "SocketIO: ${if (connected) "Connected" else "Disconnected"}"
        Text(text = status, style = MaterialTheme.typography.headlineSmall)
    }

    @Composable
    fun MapTileImage() {
        val bitmap by MapTilesApiClient.currentTile
            .map { it?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }
            .collectAsState(initial = null)

        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Map Tile",
                contentScale = ContentScale.Crop
            )
        }
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

    @Composable
    fun PlacesSearchField(
        placesClient: PlacesClient,
        onPlaceSelected: (placeId: String) -> Unit,
        onStartNavigation: () -> Unit
    ) {
        var query by remember { mutableStateOf("") }
        var suggestions by remember { mutableStateOf(listOf<AutocompletePrediction>()) }
        var selectedPlaceId by remember { mutableStateOf<String?>(null) }
        var selectedPlaceName by remember { mutableStateOf<String?>(null) }
        val coroutineScope = rememberCoroutineScope()
        var searchJob: Job? by remember { mutableStateOf(null) }

        Column(modifier = Modifier.padding(16.dp)) {
            TextField(
                value = query,
                onValueChange = {
                    query = it

                    searchJob?.cancel()
                    searchJob = coroutineScope.launch {
                        delay(300)
                        if (query.isNotEmpty()) {
                            val request = FindAutocompletePredictionsRequest.builder()
                                .setQuery(query)
                                .build()

                            placesClient.findAutocompletePredictions(request)
                                .addOnSuccessListener { result ->
                                    suggestions = result.autocompletePredictions
                                }
                                .addOnFailureListener {
                                    suggestions = emptyList()
                                }
                        } else {
                            suggestions = emptyList()
                        }
                    }
                },
                label = { Text("Search places") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn {
                items(suggestions) { suggestion ->
                    Text(
                        text = suggestion.getFullText(null).toString(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val placeName = suggestion.getFullText(null).toString()
                                query = placeName
                                selectedPlaceId = suggestion.placeId
                                selectedPlaceName = placeName
                                suggestions = emptyList()

                                // Prepare the route immediately
                                onPlaceSelected(suggestion.placeId)
                            }
                            .padding(8.dp)
                    )
                    Divider()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Show Start Directions button if a place is selected
            selectedPlaceId?.let {
                Button(
                    onClick = onStartNavigation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Directions to ${selectedPlaceName ?: "Selected Place"}")
                }
            }
        }
    }



}
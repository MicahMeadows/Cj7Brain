package com.micah.cj7brain

import android.location.Location
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.micah.cj7brain.api.NavigatorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles the "search & navigate" buttons (e.g. Taco Bell): text-search for a
 * query, let it run for a few seconds, then pick the closest result and start
 * turn-by-turn directions to it immediately.
 */
object PlaceSearchNavigator {

    // How long to "search" before committing to the nearest result.
    private const val SEARCH_WINDOW_MS = 5000L
    // Bias results to roughly this radius (meters) around the current location.
    private const val SEARCH_RADIUS_METERS = 50_000.0

    private var placesClient: PlacesClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(client: PlacesClient) {
        placesClient = client
    }

    fun searchAndNavigate(query: String) {
        val client = placesClient
        if (client == null) {
            Log.w("SearchNav", "PlacesClient not initialized; ignoring '$query'")
            return
        }

        val origin = NavigatorManager.lastKnownLocation
        if (origin == null) {
            Log.w("SearchNav", "No known location yet; results won't be distance-ranked")
        }

        val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)
        val builder = SearchByTextRequest.builder(query, fields)
            .setMaxResultCount(10)
        origin?.let {
            builder
                .setLocationBias(
                    CircularBounds.newInstance(
                        LatLng(it.latitude, it.longitude), SEARCH_RADIUS_METERS
                    )
                )
                .setRankPreference(SearchByTextRequest.RankPreference.DISTANCE)
        }

        // Latest search results, written from the Places callback and read by
        // the timer coroutine once the search window elapses.
        val resultsRef = AtomicReference<List<Place>?>(null)

        Log.d("SearchNav", "Searching for '$query'...")
        client.searchByText(builder.build())
            .addOnSuccessListener { response ->
                Log.d("SearchNav", "Got ${response.places.size} result(s) for '$query'")
                resultsRef.set(response.places)
            }
            .addOnFailureListener { e ->
                Log.e("SearchNav", "Search for '$query' failed", e)
            }

        scope.launch {
            delay(SEARCH_WINDOW_MS)

            val places = resultsRef.get()
            if (places.isNullOrEmpty()) {
                Log.w("SearchNav", "No results for '$query' after ${SEARCH_WINDOW_MS}ms")
                return@launch
            }

            val closest = pickClosest(places, origin)
            val placeId = closest?.id
            if (placeId == null) {
                Log.w("SearchNav", "Closest result has no place id; cannot navigate")
                return@launch
            }

            Log.d("SearchNav", "Navigating to closest: ${closest.name} ($placeId)")
            withContext(Dispatchers.Main) {
                NavigatorManager.prepareRoute(placeId)
                NavigatorManager.startNavigation()
            }
        }
    }

    /**
     * Nearest place to [origin] by straight-line distance. If [origin] or the
     * places' coordinates are unavailable, falls back to the first result
     * (the Places API already returns them best-first).
     */
    private fun pickClosest(places: List<Place>, origin: Location?): Place? {
        if (origin == null) return places.firstOrNull()
        return places.minByOrNull { place ->
            val latLng = place.latLng ?: return@minByOrNull Float.MAX_VALUE
            val out = FloatArray(1)
            Location.distanceBetween(
                origin.latitude, origin.longitude,
                latLng.latitude, latLng.longitude,
                out
            )
            out[0]
        }
    }
}

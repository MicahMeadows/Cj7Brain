package com.micah.cj7brain.api

import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.libraries.navigation.NavigationApi.ErrorCode
import com.google.android.libraries.navigation.NavigationApi.NavigatorListener
import com.google.android.libraries.navigation.NavigationApi.getNavigator
import com.google.android.libraries.navigation.Navigator
import com.micah.cj7brain.AppLogicService

import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator.RouteChangedListener
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.TermsAndConditionsCheckOption
import com.google.android.libraries.navigation.Waypoint
import com.micah.cj7brain.SocketManager
import java.net.Socket


object NavigatorManager {
    lateinit var mNavigator: Navigator
    var pendingPlaceId: String? = null // store selected place but don't start guidance
    private var mRoadSnappedLocationProvider: RoadSnappedLocationProvider? = null

    fun setupRouteChangeListener() {
        mNavigator.addRouteChangedListener {
            Log.d("Navigator", "Route changed!")
            mNavigator.let {
                val routeSegments = it.routeSegments;
                SocketManager.broadcastRouteSegments(routeSegments)
            }
        }
    }

    fun setupRemainingTimeDistListener() {
        mNavigator.addRemainingTimeOrDistanceChangedListener(60, 50, {
            mNavigator.currentTimeAndDistance?.let {
                SocketManager.broadcastTimeAndDistance(it.meters, it.seconds)
            }
        })
    }

    fun setupLocationListener(application: Application) {
        mRoadSnappedLocationProvider = NavigationApi.getRoadSnappedLocationProvider(application)
        mRoadSnappedLocationProvider?.addLocationListener(object : RoadSnappedLocationProvider.LocationListener {
            override fun onLocationChanged(newLocation: Location?) {
                Log.d("Location", "New location update: ${newLocation.toString()}")
                // TODO: update new location update to socketio
                newLocation?.let {
                    SocketManager.updateLocation(newLocation.latitude, newLocation.longitude, newLocation.bearing)
                }
            }

            override fun onRawLocationUpdate(newLocation: Location) {
                Log.d("Location", "Raw location update: ${newLocation.toString()}")
                // TODO: update raw location update to socketio
                // MapTilesApiClient.setCoordinates(newLocation.latitude, newLocation.longitude)
            }
        })
    }

    fun startNavigation() {
        mNavigator.startGuidance()

    }

    fun prepareRoute(placeId: String) {
        val waypoint = try {
            Waypoint.builder().setPlaceIdString(placeId).build()
        } catch (e: Waypoint.UnsupportedPlaceIdException) {
            Log.d("Navigator", "Place ID unsupported")
            return
        }

        // Prepare the route but do NOT start guidance yet
        val pendingRoute = mNavigator.setDestination(waypoint)
        pendingRoute?.setOnResultListener { code ->
            when (code) {
                Navigator.RouteStatus.OK -> {
                    // Route is ready, can now enable the button to start guidance
                }
                Navigator.RouteStatus.ROUTE_CANCELED -> Log.d("Navigator", "Route canceled.")
                Navigator.RouteStatus.NO_ROUTE_FOUND, Navigator.RouteStatus.NETWORK_ERROR -> Log.d("Navigator", "Error preparing route: $code")
                else -> Log.d("Navigator", "Error preparing route: $code")
            }
        }
        pendingPlaceId = placeId
    }

    fun initializeNavigationApi(context: Context) {
        val app = context.applicationContext as? Application
        app?.let {
            NavigationApi.getNavigator(
                it,
                object : NavigatorListener {
                    override fun onNavigatorReady(navigator: Navigator) {
                        // store a reference to the Navigator object
                        mNavigator = navigator

//                    val isNavInfoReceivingServiceRegistered = navigator.registerServiceForNavUpdates(
//                        packageName,
//                        AppLogicService::class.java.name,
//                        2
//                    )                    // code to start guidance will go here

                        setupLocationListener(application = context.applicationContext as Application)
                        setupRouteChangeListener()
                        setupRemainingTimeDistListener()
                    }

                    override fun onError(@ErrorCode errorCode: Int) {
                        when (errorCode) {
                            ErrorCode.NOT_AUTHORIZED -> {

                            }
                            ErrorCode.TERMS_NOT_ACCEPTED -> {

                            }
                        }
                    }
                },
            )
        }

    }
}
package com.micah.cj7brain.api

import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
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


private class IncomingNavStepHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
        if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {

            NavigatorManager.handleTurnByTurnMessage(msg)
        } else {
            super.handleMessage(msg)

        }
    }
}

object NavigatorManager {
    lateinit var mNavigator: Navigator
    var pendingPlaceId: String? = null // store selected place but don't start guidance
    private var mRoadSnappedLocationProvider: RoadSnappedLocationProvider? = null

    private lateinit var turnByTurnManager: TurnByTurnManager
    lateinit var incomingMessenger: Messenger
    lateinit var handlerThread: HandlerThread


    fun setupRouteChangeListener() {
        mNavigator.addRouteChangedListener {
            Log.d("Navigator", "Route changed!")
            emitRouteSegments()
        }
    }

    fun emitRouteSegments() {
        mNavigator.let {
            val routeSegments = it.routeSegments;
            SocketManager.broadcastRouteSegments(routeSegments)
        }
    }

    fun emitTimeAndDistance() {
        mNavigator.currentTimeAndDistance?.let {
            SocketManager.broadcastTimeAndDistance(it.meters, it.seconds)
        }
    }

    fun handlePageReload() {
        emitTimeAndDistance()
        emitRouteSegments()
    }

    fun setupRemainingTimeDistListener() {
        mNavigator.addRemainingTimeOrDistanceChangedListener(60, 50, {
            emitTimeAndDistance()
        })
    }

    fun setupLocationListener(application: Application) {
        mRoadSnappedLocationProvider = NavigationApi.getRoadSnappedLocationProvider(application)
        mRoadSnappedLocationProvider?.addLocationListener(object : RoadSnappedLocationProvider.LocationListener {
            override fun onLocationChanged(newLocation: Location?) {
                Log.d("Location", "New location update: ${newLocation.toString()}")
                newLocation?.let {
                    // TODO: replace with device speed so it works without nav
                    val speedMph = newLocation.speed * 2.23694
                    SocketManager.updateLocation(newLocation.latitude, newLocation.longitude, newLocation.bearing, speedMph.toFloat()   )
                }
            }

            override fun onRawLocationUpdate(newLocation: Location) {
                Log.d("Location", "Raw location update: ${newLocation.toString()}")
//                val speedMph = newLocation.speed * 2.23694
//                SocketManager.updateLocation(newLocation.latitude, newLocation.longitude, newLocation.bearing, speedMph.toFloat()   )
            }
        })
    }

    fun startNavigation() {
        mNavigator.stopGuidance()
        pendingPlaceId?.let {
            prepareRoute(it)
        }
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

    fun handleTurnByTurnMessage(msg: Message) {
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

        navInfo?.currentStep?.let {
            SocketManager.broadcastTurnByTurnEvent(
                it.fullRoadName ?: "",
                maneuver = it.maneuver,
                instruct = it.fullInstructionText ?: "",
                side = it.drivingSide,
                meters = it.distanceFromPrevStepMeters,
                seconds = it.timeFromPrevStepSeconds,
                step = it.stepNumber,
                exit = it.exitNumber
            )
        }

    }

    fun setupTurnByTurnThread() {
        turnByTurnManager = TurnByTurnManager.createInstance()
        handlerThread = HandlerThread(
            "NavInfoReceivingService",
            Process.THREAD_PRIORITY_DEFAULT

        )
        handlerThread.start()

        incomingMessenger = Messenger(IncomingNavStepHandler(handlerThread.looper))

    }

    fun initializeNavigationApi(context: Context) {
        val app = context.applicationContext as? Application
        app?.let {
            getNavigator(
                it,
                object : NavigatorListener {
                    override fun onNavigatorReady(navigator: Navigator) {
                        // store a reference to the Navigator object
                        mNavigator = navigator

                        val registered = mNavigator.registerServiceForNavUpdates(
                            context.packageName,
                            AppLogicService::class.java.name,
                            2
                        )
                        Log.d("Navigator", "service registered = $registered")

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
package com.micah.cj7brain.api

import android.app.Activity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var pendingStart = false   // user tapped "start" before the Navigator was ready
    private var mRoadSnappedLocationProvider: RoadSnappedLocationProvider? = null

    private lateinit var turnByTurnManager: TurnByTurnManager
    lateinit var incomingMessenger: Messenger
    lateinit var handlerThread: HandlerThread

    private val _navRunning = MutableStateFlow(false)
    val navRunning: StateFlow<Boolean> = _navRunning

    // Most recent known location, used to rank "nearest" search results.
    // Written from the location listener (main thread), read from the search
    // coroutine, so keep it volatile.
    @Volatile
    var lastKnownLocation: Location? = null
        private set


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
        if (_navRunning.value) {
            emitRouteSegments()
            emitTimeAndDistance()
        } else {
            SocketManager.emitRouteEnd()
        }
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
                    lastKnownLocation = it
                    // TODO: replace with device speed so it works without nav
                    val speedMph = newLocation.speed * 2.23694
                    SocketManager.updateLocation(newLocation.latitude, newLocation.longitude, newLocation.bearing, speedMph.toFloat()   )
                }
            }

            override fun onRawLocationUpdate(newLocation: Location) {
                Log.d("Location", "Raw location update: ${newLocation.toString()}")
                // Fallback so we still have a fix before guidance starts snapping.
                if (lastKnownLocation == null) lastKnownLocation = newLocation
//                val speedMph = newLocation.speed * 2.23694
//                SocketManager.updateLocation(newLocation.latitude, newLocation.longitude, newLocation.bearing, speedMph.toFloat()   )
            }
        })
    }

    fun startNavigation() {
        if (!::mNavigator.isInitialized) {
            Log.w("Navigator", "startNavigation called before Navigator ready; will start once initialized")
            pendingStart = true
            return
        }
        mNavigator.stopGuidance()
        pendingPlaceId?.let {
            prepareRoute(it)
        }
        mNavigator.startGuidance()
        _navRunning.value = true
    }

    fun stopNavigation() {
        _navRunning.value = false
        SocketManager.emitRouteEnd()
        if (!::mNavigator.isInitialized) return
        mNavigator.stopGuidance()
    }

    fun prepareRoute(placeId: String) {
        // Remember the selection even if the Navigator isn't ready yet, so we can
        // prepare the route as soon as it initializes (see onNavigatorReady).
        pendingPlaceId = placeId

        // The Navigation SDK initializes asynchronously — bail out gracefully if the
        // user picked a destination before onNavigatorReady fired (avoids the
        // UninitializedPropertyAccessException crash). The pending place is prepared later.
        if (!::mNavigator.isInitialized) {
            Log.d("Navigator", "Navigator not ready yet; deferring route prep for $placeId")
            return
        }

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

    // Must be driven from an Activity: the getNavigator overload that accepts a
    // TermsAndConditionsCheckOption requires an Activity (not Application).
    fun initializeNavigationApi(activity: Activity) {
        if (::mNavigator.isInitialized) {
            Log.d("Navigator", "Navigator already initialized; skipping re-init")
            return
        }
        getNavigator(
            activity,
            object : NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    // store a reference to the Navigator object
                    mNavigator = navigator

                    val registered = mNavigator.registerServiceForNavUpdates(
                        activity.packageName,
                        AppLogicService::class.java.name,
                        2
                    )
                    Log.d("Navigator", "service registered = $registered")

                    setupLocationListener(application = activity.application)
                    setupRouteChangeListener()
                    setupRemainingTimeDistListener()

                    // Honor whatever the user did before the Navigator was ready:
                    // if they tapped "start", start guidance now (startNavigation
                    // also prepares the pending route); otherwise just prepare it.
                    if (pendingStart) {
                        pendingStart = false
                        startNavigation()
                    } else {
                        pendingPlaceId?.let { prepareRoute(it) }
                    }
                }

                override fun onError(@ErrorCode errorCode: Int) {
                    when (errorCode) {
                        ErrorCode.NOT_AUTHORIZED ->
                            Log.e("Navigator", "Nav init failed: NOT_AUTHORIZED — check the Maps API key and that the Navigation SDK is enabled for it")
                        ErrorCode.TERMS_NOT_ACCEPTED ->
                            Log.e("Navigator", "Nav init failed: TERMS_NOT_ACCEPTED")
                        ErrorCode.NETWORK_ERROR ->
                            Log.e("Navigator", "Nav init failed: NETWORK_ERROR")
                        ErrorCode.LOCATION_PERMISSION_MISSING ->
                            Log.e("Navigator", "Nav init failed: LOCATION_PERMISSION_MISSING")
                        else ->
                            Log.e("Navigator", "Nav init failed: unknown code=$errorCode")
                    }
                }
            },
            // Skip the SDK's built-in T&C gate — without this the SDK returns
            // TERMS_NOT_ACCEPTED and the Navigator never initializes.
            TermsAndConditionsCheckOption.SKIPPED,
        )
    }


}
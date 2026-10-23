package com.wkq.util.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class GoogleAppLocationProvider(
    context: Context
) : AppLocationProvider {

    private val appContext = context.applicationContext
    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private var callback: LocationCallback? = null
    private var singleRequest: CancellationTokenSource? = null
    private var requestGeneration = 0L

    override var lastLocation: Location? = null
        private set

    @SuppressLint("MissingPermission")
    override fun start(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        if (callback != null) {
            LocationLog.d(TAG, "start: callback already active")
            return
        }
        if (!hasAnyLocationPermission()) {
            LocationLog.d(TAG, "start: no location permission")
            onLocationUnavailable()
            return
        }

        val request = LocationRequest.Builder(currentPriority(), 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(hasFineLocationPermission())
            .build()
        LocationLog.d(TAG, "start: fine=${hasFineLocationPermission()}, coarse=${hasCoarseLocationPermission()}, priority=${currentPriority()}")

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (callback !== this) return
                LocationLog.d(TAG, "onLocationResult: count=${result.locations.size}, last=${result.lastLocation?.provider}")
                val location = result.locations
                    .filter { it.isFreshEnough() }
                    .minByOrNull { it.accuracy }
                    ?: result.lastLocation
                if (location != null) {
                    acceptLocation(location)?.let(onLocationChanged)
                } else {
                    onLocationUnavailable()
                }
            }
        }
        callback = locationCallback

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener(mainExecutor) { location ->
                    if (callback !== locationCallback) return@addOnSuccessListener
                    LocationLog.d(TAG, "lastLocation success: location=${location?.provider}, fresh=${location?.isFreshEnough() == true}")
                    if (location != null && location.isFreshEnough()) {
                        acceptLocation(location)?.let(onLocationChanged)
                    } else {
                        LocationLog.d(TAG, "lastLocation unusable, requestCurrentLocation")
                        requestCurrentLocation(onLocationChanged, onLocationUnavailable)
                    }
                }
                .addOnFailureListener(mainExecutor) {
                    if (callback !== locationCallback) return@addOnFailureListener
                    LocationLog.e(TAG, "lastLocation failure: ${it.message.orEmpty()}")
                    requestCurrentLocation(onLocationChanged, onLocationUnavailable)
                }

            fusedLocationClient.requestLocationUpdates(request, locationCallback, null)
            LocationLog.d(TAG, "requestLocationUpdates registered")
        } catch (_: SecurityException) {
            callback = null
            LocationLog.e(TAG, "start: SecurityException")
            onLocationUnavailable()
        }
    }

    override fun stop() {
        requestGeneration++
        singleRequest?.cancel()
        singleRequest = null
        LocationLog.d(TAG, "stop")
        callback?.let(fusedLocationClient::removeLocationUpdates)
        callback = null
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleUpdate(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        if (!hasAnyLocationPermission()) {
            LocationLog.d(TAG, "requestSingleUpdate: no location permission")
            onLocationUnavailable()
            return
        }
        LocationLog.d(TAG, "requestSingleUpdate")
        requestCurrentLocation(onLocationChanged, onLocationUnavailable)
    }

    private fun acceptLocation(location: Location): Location? {
        if (!location.isFreshEnough()) return null
        val current = lastLocation
        val shouldReplace = current == null ||
            !current.isFreshEnough() ||
            location.accuracy <= current.accuracy + ACCURACY_TOLERANCE_METERS
        if (shouldReplace) {
            lastLocation = location
            LocationLog.d(TAG, "acceptLocation: accepted provider=${location.provider}, lat=${location.latitude}, lon=${location.longitude}, acc=${if (location.hasAccuracy()) location.accuracy else -1f}")
            return location
        }
        LocationLog.d(TAG, "acceptLocation: keep current provider=${current?.provider}, acc=${current?.accuracy}")
        return current
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        singleRequest?.cancel()
        val generation = ++requestGeneration
        val cancellation = CancellationTokenSource().also { singleRequest = it }
        val request = CurrentLocationRequest.Builder()
            .setPriority(currentPriority())
            .setMaxUpdateAgeMillis(MAX_LOCATION_AGE_MS)
            .build()
        LocationLog.d(TAG, "requestCurrentLocation: priority=${currentPriority()}")
        try {
            fusedLocationClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener(mainExecutor) { location ->
                    if (generation != requestGeneration) return@addOnSuccessListener
                    singleRequest = null
                    LocationLog.d(TAG, "getCurrentLocation success: provider=${location?.provider}")
                    if (location != null) {
                        acceptLocation(location)?.let(onLocationChanged) ?: onLocationUnavailable()
                    } else {
                        LocationLog.d(TAG, "getCurrentLocation returned null")
                        onLocationUnavailable()
                    }
                }
                .addOnFailureListener(mainExecutor) {
                    if (generation != requestGeneration) return@addOnFailureListener
                    singleRequest = null
                    LocationLog.e(TAG, "getCurrentLocation failure: ${it.message.orEmpty()}")
                    onLocationUnavailable()
                }
        } catch (_: SecurityException) {
            cancellation.cancel()
            singleRequest = null
            LocationLog.e(TAG, "requestCurrentLocation: SecurityException")
            onLocationUnavailable()
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCoarseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyLocationPermission(): Boolean {
        return hasFineLocationPermission() || hasCoarseLocationPermission()
    }

    private fun currentPriority(): Int {
        return if (hasFineLocationPermission()) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
    }

    private fun Location.isFreshEnough(): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
        return ageMs <= MAX_LOCATION_AGE_MS
    }

    private companion object {
        private const val TAG = "FieldLocationGoogle"
        private const val MAX_LOCATION_AGE_MS = 20_000L
        private const val ACCURACY_TOLERANCE_METERS = 3f
    }
}

package com.wkq.util.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat

class SystemAppLocationProvider(
    context: Context
) : AppLocationProvider {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private var listener: LocationListener? = null
    private var singleRequest: LocationListener? = null
    private val singleHandler = android.os.Handler(appContext.mainLooper)

    override var lastLocation: Location? = null
        private set

    @SuppressLint("MissingPermission")
    override fun start(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        if (listener != null) {
            LocationLog.d(TAG, "start: listener already active")
            return
        }
        if (!hasAnyLocationPermission()) {
            LocationLog.d(TAG, "start: no location permission")
            onLocationUnavailable()
            return
        }
        val providers = enabledProviders()
        LocationLog.d(TAG, "start: providers=$providers")
        if (providers.isEmpty()) {
            onLocationUnavailable()
            return
        }

        bestLastKnownLocation()?.let {
            lastLocation = it
            LocationLog.d(TAG, "start: bestLastKnown provider=${it.provider}, lat=${it.latitude}, lon=${it.longitude}, acc=${if (it.hasAccuracy()) it.accuracy else -1f}")
            onLocationChanged(it)
        }

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                LocationLog.d(TAG, "onLocationChanged: provider=${location.provider}, lat=${location.latitude}, lon=${location.longitude}, acc=${if (location.hasAccuracy()) location.accuracy else -1f}")
                onLocationChanged(location)
            }

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (enabledProviders().isEmpty()) {
                    onLocationUnavailable()
                }
            }
        }
        listener = locationListener

        try {
            providers.forEach { provider ->
                LocationLog.d(TAG, "requestLocationUpdates: provider=$provider")
                locationManager.requestLocationUpdates(
                    provider,
                    MIN_UPDATE_INTERVAL_MS,
                    MIN_UPDATE_DISTANCE_METERS,
                    locationListener
                )
            }
        } catch (_: SecurityException) {
            listener = null
            LocationLog.e(TAG, "start: SecurityException")
            onLocationUnavailable()
        }
    }

    override fun stop() {
        singleHandler.removeCallbacksAndMessages(null)
        singleRequest?.let(locationManager::removeUpdates)
        singleRequest = null
        LocationLog.d(TAG, "stop")
        listener?.let(locationManager::removeUpdates)
        listener = null
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleUpdate(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        singleRequest?.let(locationManager::removeUpdates)
        singleRequest = null
        singleHandler.removeCallbacksAndMessages(null)
        if (!hasAnyLocationPermission()) {
            LocationLog.d(TAG, "requestSingleUpdate: no location permission")
            onLocationUnavailable()
            return
        }
        bestLastKnownLocation()?.let {
            lastLocation = it
            LocationLog.d(TAG, "requestSingleUpdate: bestLastKnown provider=${it.provider}, lat=${it.latitude}, lon=${it.longitude}, acc=${if (it.hasAccuracy()) it.accuracy else -1f}")
            onLocationChanged(it)
            return
        }

        val providers = enabledProviders()
        LocationLog.d(TAG, "requestSingleUpdate: providers=$providers")
        if (providers.isEmpty()) {
            onLocationUnavailable()
            return
        }

        var completed = false
        val singleListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed || singleRequest !== this) return
                completed = true
                singleRequest = null
                singleHandler.removeCallbacksAndMessages(null)
                lastLocation = location
                locationManager.removeUpdates(this)
                onLocationChanged(location)
            }

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        singleRequest = singleListener

        try {
            providers.forEach { provider ->
                LocationLog.d(TAG, "requestSingleUpdate updates: provider=$provider")
                locationManager.requestLocationUpdates(
                    provider,
                    SINGLE_UPDATE_INTERVAL_MS,
                    0f,
                    singleListener
                )
            }
        } catch (_: SecurityException) {
            completed = true
            locationManager.removeUpdates(singleListener)
            singleRequest = null
            LocationLog.e(TAG, "requestSingleUpdate: SecurityException")
            onLocationUnavailable()
            return
        }
        mainExecutor.execute {
            if (singleRequest !== singleListener) return@execute
            singleHandler.postDelayed({
                if (!completed && singleRequest === singleListener) {
                    completed = true
                    singleRequest = null
                    locationManager.removeUpdates(singleListener)
                    LocationLog.d(TAG, "requestSingleUpdate: timeout=${SINGLE_UPDATE_TIMEOUT_MS}ms")
                    onLocationUnavailable()
                }
            }, SINGLE_UPDATE_TIMEOUT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(): Location? {
        return enabledProviders()
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.isFreshEnough() }
            .minWithOrNull(compareBy<Location> { it.accuracyOrMax() }.thenByDescending { it.time })
    }

    private fun enabledProviders(): List<String> {
        val candidateProviders = buildList {
            if (hasFineLocationPermission()) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (hasAnyLocationPermission()) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        return candidateProviders
            .filter { provider ->
                runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
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

    private fun Location.isFreshEnough(): Boolean {
        val ageMs = if (elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - time
        }
        return ageMs <= MAX_LOCATION_AGE_MS
    }

    private fun Location.accuracyOrMax(): Float {
        return if (hasAccuracy()) accuracy else Float.MAX_VALUE
    }

    private companion object {
        private const val TAG = "FieldLocationSystem"
        private const val MAX_LOCATION_AGE_MS = 60_000L
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
        private const val SINGLE_UPDATE_INTERVAL_MS = 500L
        private const val SINGLE_UPDATE_TIMEOUT_MS = 15_000L
        private const val MIN_UPDATE_DISTANCE_METERS = 0f
    }
}

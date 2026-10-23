package com.wkq.util.location

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class FallbackAppLocationProvider(
    private val primary: AppLocationProvider,
    private val fallback: AppLocationProvider
) : AppLocationProvider {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestGeneration = 0L

    override val lastLocation: Location?
        get() = primary.lastLocation ?: fallback.lastLocation

    override fun start(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        stop()
        val generation = requestGeneration
        LocationLog.d(TAG, "start: primary=${primary.javaClass.simpleName}, fallback=${fallback.javaClass.simpleName}")
        var delivered = false
        var fallbackStarted = false

        fun deliverLocation(location: Location) {
            if (generation != requestGeneration) return
            delivered = true
            mainHandler.removeCallbacksAndMessages(FALLBACK_START_TOKEN)
            LocationLog.d(TAG, "deliverLocation: provider=${location.provider}, lat=${location.latitude}, lon=${location.longitude}, acc=${if (location.hasAccuracy()) location.accuracy else -1f}")
            onLocationChanged(location)
        }

        fun startFallback() {
            if (generation != requestGeneration || delivered || fallbackStarted) return
            fallbackStarted = true
            LocationLog.d(TAG, "startFallback: switching to ${fallback.javaClass.simpleName}")
            fallback.start(
                onLocationChanged = ::deliverLocation,
                onLocationUnavailable = {
                    if (generation == requestGeneration && !delivered) {
                        LocationLog.d(TAG, "startFallback: fallback unavailable")
                        onLocationUnavailable()
                    }
                }
            )
        }

        LocationLog.d(TAG, "start: schedule fallback timeout=${PRIMARY_TIMEOUT_MS}ms")
        mainHandler.postAtTime({ startFallback() }, FALLBACK_START_TOKEN, SystemClock.uptimeMillis() + PRIMARY_TIMEOUT_MS)

        primary.start(
            onLocationChanged = ::deliverLocation,
            onLocationUnavailable = {
                LocationLog.d(TAG, "start: primary unavailable, trigger fallback")
                startFallback()
            }
        )
    }

    override fun stop() {
        requestGeneration++
        LocationLog.d(TAG, "stop")
        mainHandler.removeCallbacksAndMessages(FALLBACK_START_TOKEN)
        mainHandler.removeCallbacksAndMessages(FALLBACK_SINGLE_TOKEN)
        primary.stop()
        fallback.stop()
    }

    override fun requestSingleUpdate(
        onLocationChanged: (Location) -> Unit,
        onLocationUnavailable: () -> Unit
    ) {
        stop()
        val generation = requestGeneration
        LocationLog.d(TAG, "requestSingleUpdate: primary=${primary.javaClass.simpleName}, fallback=${fallback.javaClass.simpleName}")
        var delivered = false
        var fallbackStarted = false

        fun deliverLocation(location: Location) {
            if (generation != requestGeneration || delivered) return
            delivered = true
            mainHandler.removeCallbacksAndMessages(FALLBACK_SINGLE_TOKEN)
            stop()
            LocationLog.d(TAG, "requestSingleUpdate deliver: provider=${location.provider}, lat=${location.latitude}, lon=${location.longitude}, acc=${if (location.hasAccuracy()) location.accuracy else -1f}")
            onLocationChanged(location)
        }

        fun requestFallback() {
            if (generation != requestGeneration || delivered || fallbackStarted) return
            fallbackStarted = true
            LocationLog.d(TAG, "requestSingleUpdate: switching to fallback")
            fallback.requestSingleUpdate(
                onLocationChanged = ::deliverLocation,
                onLocationUnavailable = {
                    if (generation == requestGeneration && !delivered) {
                        delivered = true
                        stop()
                        LocationLog.d(TAG, "requestSingleUpdate: fallback unavailable")
                        onLocationUnavailable()
                    }
                }
            )
        }

        mainHandler.postAtTime(
            { requestFallback() },
            FALLBACK_SINGLE_TOKEN,
            SystemClock.uptimeMillis() + PRIMARY_TIMEOUT_MS
        )

        primary.requestSingleUpdate(
            onLocationChanged = ::deliverLocation,
            onLocationUnavailable = {
                LocationLog.d(TAG, "requestSingleUpdate: primary unavailable, trigger fallback")
                requestFallback()
            }
        )
    }

    private companion object {
        private const val TAG = "FieldLocationFallback"
        private const val PRIMARY_TIMEOUT_MS = 2_000L
        private val FALLBACK_START_TOKEN = Any()
        private val FALLBACK_SINGLE_TOKEN = Any()
    }
}

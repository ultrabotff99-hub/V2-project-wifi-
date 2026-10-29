package com.tonix.wifireader

import kotlin.math.pow

/** Estimates derived from live RSSI readings. These are approximations based on
 *  standard signal path-loss models, not exact measurements. */
object SignalMath {
    fun estimateDistanceMeters(rssi: Int): Double {
        val txPowerAt1m = -40.0
        val pathLossExponent = 2.7
        return 10.0.pow((txPowerAt1m - rssi) / (10 * pathLossExponent))
    }

    fun estimateSpeedMbps(rssi: Int, freqMhz: Int): Int {
        val band5 = freqMhz >= 4900
        return when {
            rssi >= -50 -> if (band5) 433 else 150
            rssi >= -65 -> if (band5) 300 else 100
            rssi >= -75 -> if (band5) 150 else 54
            else -> 20
        }
    }

    /** Beep interval in ms: shorter (faster beeps) as the live signal gets stronger. */
    fun beepIntervalMs(rssi: Int): Long {
        val frac = ((rssi + 95f) / 65f).coerceIn(0f, 1f)
        return (900 - frac * 820).toLong().coerceIn(80, 900)
    }
}

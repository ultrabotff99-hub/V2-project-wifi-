package com.tonix.wifireader

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class TrackerScreen(ctx: Context, private val targetBssid: String, private val targetSsid: String) : LinearLayout(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    private val signalTxt: TextView
    private val distTxt: TextView
    private var running = true

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#05070D"))
        setPadding(40, 100, 40, 40)
        gravity = Gravity.CENTER_HORIZONTAL

        addView(TextView(ctx).apply {
            text = "🎯 TARGETING"; setTextColor(Color.parseColor("#FFC400")); textSize = 13f
        })
        addView(TextView(ctx).apply {
            text = targetSsid; setTextColor(Color.parseColor("#00E5FF")); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 6, 0, 40)
        })
        signalTxt = TextView(ctx).apply { textSize = 46f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 10) }
        addView(signalTxt)
        distTxt = TextView(ctx).apply { textSize = 16f; setTextColor(Color.parseColor("#7FDBFF")) }
        addView(distTxt)
        addView(TextView(ctx).apply {
            text = "Beep speeds up as the live signal strengthens — based on real-time signal strength, not exact GPS distance."
            setTextColor(Color.parseColor("#80DEEA")); textSize = 11f; gravity = Gravity.CENTER; setPadding(10, 60, 10, 0)
        })
        startLoop(ctx)
    }

    private fun startLoop(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val loop = object : Runnable {
            override fun run() {
                if (!running) return
                @Suppress("DEPRECATION") wm.startScan()
                h.postDelayed({
                    if (!running) return@postDelayed
                    val res = try { wm.scanResults } catch (e: SecurityException) { emptyList() }
                    val match = res.find { it.BSSID == targetBssid }
                    if (match != null) {
                        val rssi = match.level
                        signalTxt.text = "$rssi dBm"
                        val dist = SignalMath.estimateDistanceMeters(rssi)
                        distTxt.text = "~%.1f m away (estimated)".format(dist)
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                        h.postDelayed(this, SignalMath.beepIntervalMs(rssi))
                    } else {
                        signalTxt.text = "NO SIGNAL"
                        distTxt.text = "Out of range"
                        h.postDelayed(this, 1500)
                    }
                }, 1000)
            }
        }
        h.post(loop)
    }

    fun stop() { running = false; try { tone.release() } catch (e: Exception) {} }
}

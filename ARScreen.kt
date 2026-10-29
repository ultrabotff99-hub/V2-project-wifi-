package com.tonix.wifireader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Camera
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView

@Suppress("DEPRECATION")
class ARScreen(ctx: Context, private val targetBssid: String, private val targetSsid: String) : FrameLayout(ctx) {
    private var camera: Camera? = null
    private val h = Handler(Looper.getMainLooper())
    private val overlay: TextView
    private var running = true

    init {
        val surface = SurfaceView(ctx)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surface.holder.setFormat(PixelFormat.TRANSLUCENT)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    camera = Camera.open()
                    camera?.setPreviewDisplay(holder)
                    camera?.startPreview()
                } catch (e: Exception) { }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                try { camera?.stopPreview(); camera?.release() } catch (e: Exception) {}
                camera = null
            }
        })

        overlay = TextView(ctx).apply {
            setTextColor(Color.parseColor("#00FFC6")); textSize = 18f; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#AA05070D")); setPadding(36, 30, 36, 30)
            text = "Scanning..."
        }
        addView(overlay, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val note = TextView(ctx).apply {
            text = "Live reading for \"$targetSsid\" — based on real signal strength, not a directional sensor"
            setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f; gravity = Gravity.CENTER
            setPadding(20, 14, 20, 14); setBackgroundColor(Color.parseColor("#CC05070D"))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

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
                        val mbps = SignalMath.estimateSpeedMbps(match.level, match.frequency)
                        val dist = SignalMath.estimateDistanceMeters(match.level)
                        overlay.text = "$targetSsid\n${match.level} dBm\n~$mbps Mbps\n~%.1f m".format(dist)
                    } else {
                        overlay.text = "$targetSsid\nNO SIGNAL"
                    }
                    h.postDelayed(this, 1800)
                }, 1000)
            }
        }
        h.post(loop)
    }

    fun stop() { running = false; try { camera?.release() } catch (e: Exception) {}; camera = null }
}

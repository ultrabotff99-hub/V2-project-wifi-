package com.tonix.wifireader

import android.content.Context
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random

data class ApPin(val ssid: String, val point: GeoPoint, val approximate: Boolean, val distanceMeters: Double)

class PulseOverlay(private val pins: () -> List<ApPin>) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFC6"); textSize = 26f; isFakeBoldText = true
    }
    fun tick() { phase = (phase + 0.02f) % 1f }

    override fun draw(c: Canvas, proj: Projection) {
        for (pin in pins()) {
            val p = proj.toPixels(pin.point, null)
            val rangePx = proj.metersToEquatorPixels(pin.distanceMeters.toFloat())
            fill.color = Color.parseColor("#1500E5FF")
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rangePx, fill)
            val base = Color.parseColor(if (pin.approximate) "#FFC400" else "#00E5FF")
            ring.color = base; ring.alpha = (255 * (1 - phase)).toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 14f + 40f * phase, ring)
            dot.color = base; dot.style = Paint.Style.FILL; dot.alpha = 255
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, dot)
            dot.color = Color.WHITE; dot.style = Paint.Style.STROKE; dot.strokeWidth = 2f; dot.alpha = 200
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, dot)
            c.drawText(pin.ssid, p.x + 18f, p.y + 8f, label)
        }
    }
}

class SelfPulseOverlay(private val point: () -> GeoPoint?) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.parseColor("#00FFC6") }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fun tick() { phase = (phase + 0.03f) % 1f }
    override fun draw(c: Canvas, proj: Projection) {
        val gp = point() ?: return
        val p = proj.toPixels(gp, null)
        ring.alpha = (255 * (1 - phase)).toInt()
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 16f + 50f * phase, ring)
        core.shader = RadialGradient(p.x.toFloat(), p.y.toFloat(), 22f, Color.WHITE, Color.parseColor("#0000E5FF"), Shader.TileMode.CLAMP)
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 22f, core); core.shader = null
        core.color = Color.parseColor("#00E5FF")
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, core)
        core.color = Color.WHITE
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 4f, core)
    }
}

object WifiGeolocator {
    private val pool = Executors.newFixedThreadPool(4)
    fun locate(bssid: String, cb: (GeoPoint?) -> Unit) {
        pool.execute {
            val result = try {
                val url = URL("https://location.services.mozilla.com/v1/geolocate?key=test")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().put("wifiAccessPoints", JSONArray().put(JSONObject().put("macAddress", bssid)))
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val loc = json.getJSONObject("location")
                    GeoPoint(loc.getDouble("lat"), loc.getDouble("lng"))
                } else null
            } catch (e: Exception) { null }
            Handler(Looper.getMainLooper()).post { cb(result) }
        }
    }
}

class MapScreen(ctx: Context, private val myLocation: GeoPoint, networks: List<ScanResult>) : FrameLayout(ctx) {
    private val map = MapView(ctx)
    private val pins = mutableListOf<ApPin>()
    private val pulse = PulseOverlay { pins.toList() }
    private var selfPoint: GeoPoint? = myLocation
    private val selfPulse = SelfPulseOverlay { selfPoint }
    private val tickHandler = Handler(Looper.getMainLooper())
    private val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = true
    private val listener = LocationListener { loc: Location ->
        selfPoint = GeoPoint(loc.latitude, loc.longitude)
    }

    init {
        Configuration.getInstance().userAgentValue = ctx.packageName
        val darkTiles = XYTileSource("TonixDark", 0, 19, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"))
        map.setTileSource(darkTiles)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)
        map.controller.setCenter(myLocation)
        map.overlays.add(pulse)
        map.overlays.add(selfPulse)
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val note = TextView(ctx).apply {
            text = "📍 Blue/yellow pins: estimated AP range by signal strength. White glow: your live position."
            setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f; setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#CC05070D"))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))

        for (r in networks) {
            @Suppress("DEPRECATION") val ssid = r.SSID.ifEmpty { "<hidden>" }
            val jitter = 0.0006
            val fallback = GeoPoint(myLocation.latitude + Random.nextDouble(-jitter, jitter), myLocation.longitude + Random.nextDouble(-jitter, jitter))
            val dist = SignalMath.estimateDistanceMeters(r.level)
            val pin = ApPin(ssid, fallback, true, dist)
            pins.add(pin)
            WifiGeolocator.locate(r.BSSID) { real ->
                if (real != null) {
                    val idx = pins.indexOfFirst { it.ssid == ssid && it.point == fallback }
                    if (idx >= 0) pins[idx] = ApPin(ssid, real, false, dist)
                    map.invalidate()
                }
            }
        }
        startSelfUpdates()
        startPulse()
    }

    private fun startSelfUpdates() {
        try {
            locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, listener)
            locMgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, listener)
        } catch (e: SecurityException) { } catch (e: IllegalArgumentException) { }
    }

    private fun startPulse() {
        tickHandler.post(object : Runnable {
            override fun run() {
                if (!running) return
                pulse.tick(); selfPulse.tick(); map.invalidate()
                tickHandler.postDelayed(this, 40)
            }
        })
    }

    fun onResumeMap() { map.onResume() }
    fun onPauseMap() { running = false; map.onPause(); try { locMgr.removeUpdates(listener) } catch (e: Exception) {} }
}

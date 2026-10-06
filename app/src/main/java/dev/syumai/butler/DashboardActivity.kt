package dev.syumai.butler

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.MotionEvent
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Display and tap surface. Existing MainActivity and AssistantService remain available, untouched. */
class DashboardActivity : Activity() {

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "dev.syumai.butler.VOICE_START" -> {
                    Log.i("ButlerDashboard", "VOICE START received")
                    startVoiceRecording()
                }

                "dev.syumai.butler.VOICE_STOP" -> {
                    Log.i("ButlerDashboard", "VOICE STOP received")
                    stopVoiceRecording()
                }

                "dev.syumai.butler.VOICE_IDLE" -> {
                    Log.i("ButlerDashboard", "VOICE IDLE received")
                    setVoiceState("IDLE")
                }
            }
        }
    }
    @Volatile private var voiceRecording = false
    @Volatile private var voiceStopRequested = false
    private var voiceState = "IDLE"

    private lateinit var settings: Settings
    private lateinit var dashboard: DashboardView
    private lateinit var store: DashboardStore
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val startupWorker = Executors.newSingleThreadExecutor()
    private var startupRefresh: DashboardStartupRefresh? = null
    private var startupRefreshStarted = false
    private val client = ToolClient()
    private var snapshot: DashboardSnapshot? = null
    private var weather: WeatherBrief? = null
    private var fetchFailed = false
    private var dashboardBusy = false
    private var weatherBusy = false
    private var lastDashboardFetch = 0L
    private var lastWeatherFetch = 0L
    private var visible = false
    private var lastTouchAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            dashboard.updateClock()
            val now = SystemClock.elapsedRealtime()
            if (now - lastTouchAt >= 3 * 60 * 1000L) dashboard.returnToClock()
            if (lastDashboardFetch == 0L || now - lastDashboardFetch >= 60 * 60 * 1000L) refreshDashboard()
            if (lastWeatherFetch == 0L || now - lastWeatherFetch >= 30 * 60 * 1000L) refreshWeather()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val voiceFilter = IntentFilter().apply {
            addAction("dev.syumai.butler.VOICE_START")
            addAction("dev.syumai.butler.VOICE_STOP")
            addAction("dev.syumai.butler.VOICE_IDLE")
        }
        registerReceiver(voiceReceiver, voiceFilter)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        settings = Settings(this)
        store = DashboardStore(this)
        snapshot = store.cached()
        weather = cachedWeather()
        buildUi()
        Log.i("ButlerDashboard", "Initial snapshot displayed: ${snapshot?.generatedAt ?: "none"}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }
    override fun onResume() {
        super.onResume()
        visible = true
        lastTouchAt = SystemClock.elapsedRealtime()
        lastDashboardFetch = 0L
        if (Settings.dirty) { Settings.dirty = false; settings = Settings(this); lastWeatherFetch = 0L; buildUi() }
        if (!startupRefreshStarted && settings.get("dashboardRefreshToken").isNotBlank()
            && settings.get("dashboardUrl").isNotBlank() && settings.get("dashboardReadToken").isNotBlank()) {
            startStartupRefresh()
        }
        main.removeCallbacks(tick); main.post(tick)
    }
    override fun onPause() { visible = false; main.removeCallbacks(tick); super.onPause() }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) lastTouchAt = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }
    override fun onDestroy() {
        unregisterReceiver(voiceReceiver)
        startupRefresh?.cancel()
        startupWorker.shutdownNow()
        store.cancel()
        worker.shutdownNow()
        client.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildUi() {
        dashboard = DashboardView(this, settings).apply {
            onRefresh = { refreshDashboard(true) }
            onSettings = { startActivity(Intent(this@DashboardActivity, SettingsActivity::class.java)) }
            setVoiceState(voiceState)
            setSnapshot(snapshot, fetchFailed, dashboardBusy)
            setWeather(weather)
        }
        setContentView(dashboard)
    }
    private fun setVoiceState(state: String) {
        voiceState = state
        if (::dashboard.isInitialized) dashboard.setVoiceState(state)
    }
    private fun startStartupRefresh() {
        startupRefreshStarted = true
        lastDashboardFetch = SystemClock.elapsedRealtime()
        dashboardBusy = true
        dashboard.setSnapshot(snapshot, fetchFailed, true)
        val cachedAt = snapshot?.generatedAt?.toInstant()
        val refresh = DashboardStartupRefresh(
            networkReady = {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            },
            fetch = { store.fetch(settings) },
            dispatch = {
                // Wait for the preceding snapshot callback to render before sending the POST.
                val rendered = CountDownLatch(1)
                main.post { rendered.countDown() }
                check(rendered.await(2, TimeUnit.SECONDS)) { "Snapshot display timed out" }
                store.requestRefresh(settings)
            },
            onSnapshot = { current -> main.post {
                if (!isDestroyed) {
                    if (snapshot == null || !current.generatedAt.toInstant().isBefore(snapshot!!.generatedAt.toInstant())) {
                        snapshot = current
                        fetchFailed = false
                    }
                    dashboard.setSnapshot(snapshot, fetchFailed, true)
                    Log.i("ButlerDashboard", "Startup snapshot received: ${current.generatedAt}")
                }
            } },
            onStatus = { status -> Log.i("ButlerDashboard", "Startup refresh: $status") },
        )
        startupRefresh = refresh
        startupWorker.execute {
            try { refresh.run(cachedAt) }
            finally { main.post {
                if (!isDestroyed) {
                    dashboardBusy = false
                    dashboard.setSnapshot(snapshot, fetchFailed)
                }
            } }
        }
    }
    private fun refreshDashboard(force: Boolean = false) {
        if (dashboardBusy) return
        val now = SystemClock.elapsedRealtime()
        if (force && now - lastDashboardFetch < 1500) return
        lastDashboardFetch = now
        if (settings.get("dashboardUrl").isBlank() || settings.get("dashboardReadToken").isBlank()) {
            dashboard.setSnapshot(snapshot, false)
            return
        }
        dashboardBusy = true
        dashboard.setSnapshot(snapshot, fetchFailed, true)
        worker.execute {
            val result = runCatching { store.fetch(settings) }
            main.post {
                if (isDestroyed) return@post
                dashboardBusy = false
                result.onSuccess { snapshot = it; fetchFailed = false }
                    .onFailure { error ->
                        fetchFailed = true
                        val message = error.message.orEmpty()
                        val reason = when {
                            message.matches(Regex("Dashboard HTTP [0-9]{3}")) -> message
                            message == "Dashboard schema invalid" -> "invalid schema"
                            error is java.io.IOException -> "network"
                            error is java.security.GeneralSecurityException -> "credential"
                            else -> "configuration or storage"
                        }
                        Log.w("ButlerDashboard", "Snapshot fetch failed: $reason")
                    }
                dashboard.setSnapshot(snapshot, fetchFailed)
            }
        }
    }
    private fun startVoiceRecording() {
        if (voiceRecording) {
            Log.i("ButlerDashboard", "VOICE START ignored: already recording")
            return
        }

        voiceRecording = true
        voiceStopRequested = false
        setVoiceState("LISTENING")

        worker.execute {
            val sampleRate = 16000
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize <= 0) {
                Log.e("ButlerDashboard", "VOICE unsupported buffer size: $minBufferSize")
                voiceRecording = false
                main.post { setVoiceState("IDLE") }
                return@execute
            }

            val bufferSize = maxOf(minBufferSize, 2048)
            val file = File(cacheDir, "voice_input.pcm")

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("ButlerDashboard", "VOICE AudioRecord init failed")
                recorder.release()
                voiceRecording = false
                main.post { setVoiceState("IDLE") }
                return@execute
            }

            runCatching {
                val buffer = ByteArray(bufferSize)

                FileOutputStream(file, false).use { output ->
                    recorder.startRecording()
                    Log.i("ButlerDashboard", "VOICE recording started")

                    while (!voiceStopRequested) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            output.write(buffer, 0, count)
                        }
                    }
                }

                Log.i(
                    "ButlerDashboard",
                    "VOICE recording saved: ${file.absolutePath} ${file.length()} bytes"
                )
            }.onFailure { error ->
                Log.e("ButlerDashboard", "VOICE recording failed", error)
                main.post { setVoiceState("IDLE") }
            }

            runCatching { recorder.stop() }
            recorder.release()

            voiceRecording = false
            voiceStopRequested = false
        }
    }

    private fun stopVoiceRecording() {
        if (!voiceRecording) {
            Log.i("ButlerDashboard", "VOICE STOP ignored: not recording")
            return
        }

        voiceStopRequested = true
        setVoiceState("TRANSCRIBING")
        Log.i("ButlerDashboard", "VOICE STOP requested")
    }

    private fun recordMicTest() {
        worker.execute {
            val sampleRate = 16000
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize <= 0) {
                Log.e("ButlerDashboard", "MIC TEST unsupported buffer size: $minBufferSize")
                return@execute
            }

            val bufferSize = maxOf(minBufferSize, 2048)
            val file = File(cacheDir, "mic_test.pcm")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("ButlerDashboard", "MIC TEST AudioRecord init failed")
                recorder.release()
                return@execute
            }

            runCatching {
                val buffer = ByteArray(bufferSize)
                FileOutputStream(file, false).use { output ->
                    recorder.startRecording()
                    Log.i("ButlerDashboard", "MIC TEST recording started")

                    val endAt = SystemClock.elapsedRealtime() + 5000L
                    while (SystemClock.elapsedRealtime() < endAt) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }

                Log.i(
                    "ButlerDashboard",
                    "MIC TEST saved: ${file.absolutePath} ${file.length()} bytes"
                )
            }.onFailure { error ->
                Log.e("ButlerDashboard", "MIC TEST failed", error)
            }

            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun cachedWeather(): WeatherBrief? = runCatching {
        val obj = JSONObject(settings.get("dashboardWeather"))
        if (obj.getString("lat") != settings.get("latitude") || obj.getString("lon") != settings.get("longitude")) return null
        WeatherBrief(obj.getInt("temperature"), obj.getString("icon"), obj.getString("condition"), obj.getInt("high"), obj.getInt("low"))
    }.getOrNull()
    private fun refreshWeather() {
        if (weatherBusy) return
        lastWeatherFetch = SystemClock.elapsedRealtime()
        val latText = settings.get("latitude"); val lonText = settings.get("longitude")
        val lat = latText.toDoubleOrNull(); val lon = lonText.toDoubleOrNull()
        if (lat == null || lon == null) { weather = null; dashboard.setWeather(null); return }
        weatherBusy = true
        worker.execute {
            val result = runCatching {
                val forecast = WeatherStore.current(client, lat, lon, SystemClock.elapsedRealtime(), 30 * 60 * 1000L)
                val scene = WeatherScene.of(forecast.currentCode, forecast.isDay)
                val today = forecast.days.firstOrNull()
                WeatherBrief(forecast.currentTemp.roundToInt(), WeatherFormat.emoji(scene),
                    getString(WeatherFormat.labelRes(scene)), today?.tempMax?.roundToInt() ?: forecast.currentTemp.roundToInt(),
                    today?.tempMin?.roundToInt() ?: forecast.currentTemp.roundToInt())
            }
            main.post {
                weatherBusy = false
                if (isDestroyed || settings.get("latitude") != latText || settings.get("longitude") != lonText) return@post
                result.onSuccess { brief ->
                    weather = brief
                    val raw = JSONObject().put("lat", latText).put("lon", lonText)
                        .put("temperature", brief.temperature).put("icon", brief.icon).put("condition", brief.condition)
                        .put("high", brief.high).put("low", brief.low)
                    settings.set("dashboardWeather", raw.toString())
                }
                dashboard.setWeather(weather)
            }
        }
    }
}

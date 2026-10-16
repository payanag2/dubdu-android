package com.payanag2.dubdu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioDubbingService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_API_KEY = "api_key"
        private const val CHANNEL_ID = "dubdu_live_dubbing"
        private const val NOTIFICATION_ID = 7102
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
    }

    private val running = AtomicBoolean(false)
    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var socket: WebSocket? = null
    private var captureThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        val key = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        if (projectionData == null || key.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification("در حال آماده‌سازی دوبلهٔ زنده")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else startForeground(NOTIFICATION_ID, notification)

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, projectionData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, android.os.Handler(mainLooper))
            connectGemini(key)
        } catch (e: Exception) {
            updateNotification("خطا: ${e.message ?: "شروع ضبط ناموفق بود"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun connectGemini(key: String) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject()
                    .put("setup", JSONObject()
                        .put("model", "models/gemini-3.5-live-translate-preview")
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject().put("languageCode", "fa-IR")))
                        .put("systemInstruction", JSONObject().put("parts",
                            org.json.JSONArray().put(JSONObject().put("text",
                                "You are a real-time video dubbing translator. Translate all incoming speech into natural spoken Persian (Farsi). Do not answer or converse; only translate the speech. Preserve meaning and tone, and do not describe sounds.")))))
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        startAudioCapture()
                        updateNotification("دوبلهٔ زنده فعال است")
                    }
                    val parts = root.optJSONObject("serverContent")
                        ?.optJSONObject("modelTurn")
                        ?.optJSONArray("parts") ?: return
                    for (i in 0 until parts.length()) {
                        val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                        val encoded = inline.optString("data")
                        if (encoded.isNotEmpty()) playAudio(Base64.decode(encoded, Base64.DEFAULT))
                    }
                } catch (_: Exception) {
                    // Ignore malformed or non-audio protocol events.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateNotification("اتصال ترجمه قطع شد: ${t.message ?: "خطای شبکه"}")
            }
        })
    }

    private fun startAudioCapture() {
        if (!running.compareAndSet(false, true)) return
        val mediaProjection = projection ?: return
        try {
            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(INPUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, INPUT_RATE * 2 / 2)
            recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            val outputFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUTPUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val outBuffer = AudioTrack.getMinBufferSize(
                OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(OUTPUT_RATE)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(outputFormat)
                .setBufferSizeInBytes(outBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play() }

            recorder?.startRecording()
            captureThread = thread(name = "DubDuAudioCapture") {
                val buffer = ByteArray(3200) // ~100 ms at 16 kHz, mono, PCM16
                while (running.get()) {
                    val count = try { recorder?.read(buffer, 0, buffer.size) ?: -1 }
                    catch (_: Exception) { -1 }
                    if (count > 0) {
                        val chunk = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        val msg = JSONObject().put("realtimeInput",
                            JSONObject().put("audio", JSONObject()
                                .put("mimeType", "audio/pcm;rate=16000")
                                .put("data", chunk)))
                        socket?.send(msg.toString())
                    } else if (count < 0) break
                }
            }
        } catch (e: Exception) {
            running.set(false)
            updateNotification("ضبط صدا ناموفق بود: ${e.message ?: "خطا"}")
        }
    }

    @Synchronized
    private fun playAudio(bytes: ByteArray) {
        try {
            val audioTrack = track ?: return
            audioTrack.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DubDu live dubbing", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("DubDu — دوبلهٔ زنده")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) { }
        track?.release()
        track = null
        socket?.close(1000, "Service stopped")
        socket = null
        projection?.stop()
        projection = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }
}

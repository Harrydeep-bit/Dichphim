package com.quang.dichphim

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Dịch vụ chạy nền: thu âm thanh đang phát (Chrome, YouTube...), cắt thành từng câu,
 * gửi đi dịch, rồi hiện phụ đề nổi và/hoặc đọc to tiếng Việt.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.quang.dichphim.STOP"
        private const val CHANNEL = "dichphim"
        private const val RATE = 16000
        private const val FRAME = RATE / 10          // 100 ms
        @Volatile var running = false
    }

    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private var overlay: Overlay? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val speaking = ConcurrentHashMap.newKeySet<String>()
    private val recent = ArrayDeque<String>()
    @Volatile private var backoffUntil = 0L
    @Volatile private var stopping = false
    private var heardAudio = false
    @Volatile private var vision: Vision? = null
    private var warnedBlack = false

    private val prefs by lazy { getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        vision?.onRotate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForegroundNow()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)

        if (data == null) {
            Bus.status("Thiếu quyền ghi âm thanh màn hình. Bấm Bắt đầu lại.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data)
            if (p == null) {
                Bus.status("Không lấy được quyền thu âm thanh.")
                stopSelf(); return START_NOT_STICKY
            }
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { ui.post { stopSelf() } }
            }, ui)
        } catch (e: Exception) {
            Bus.status("Lỗi khởi động: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        running = true
        setupTts()
        if (!startCapture()) {
            stopSelf(); return START_NOT_STICKY
        }
        if (prefs.getBoolean(MainActivity.K_VISION, true)) {
            val v = Vision(this, projection!!)
            if (v.start()) vision = v
        }
        Bus.status("Đang nghe… Hãy mở Chrome và phát phim.")
        return START_NOT_STICKY
    }

    // ---------------- Thông báo chạy nền ----------------

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dịch Phim", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Dịch Phim đang nghe")
            .setContentText("Đang dịch âm thanh phim sang tiếng Việt")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Dừng", stopPi).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ---------------- Đọc to ----------------

    private fun setupTts() {
        tts = TextToSpeech(this) { st ->
            val t = tts ?: return@TextToSpeech
            if (st != TextToSpeech.SUCCESS) {
                Bus.status("Máy chưa có bộ đọc giọng nói.")
                return@TextToSpeech
            }
            val r = t.setLanguage(Locale("vi", "VN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Bus.status("Máy chưa có giọng đọc tiếng Việt — xem mục Hướng dẫn trong app.")
            }
            // Giọng đọc dùng loại âm thanh "trợ lý" để app không tự thu lại giọng của chính mình.
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { id?.let { speaking.remove(it) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { id?.let { speaking.remove(it) } }
                override fun onStop(id: String?, interrupted: Boolean) { id?.let { speaking.remove(it) } }
            })
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        t.setSpeechRate(prefs.getFloat(MainActivity.K_RATE, 1.15f))
        // Nếu đang tồn đọng quá nhiều câu chưa đọc thì bỏ bớt để theo kịp phim.
        val mode = if (speaking.size >= 2) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "u" + System.nanoTime()
        speaking.add(id)
        t.speak(text, mode, null, id)
    }

    // ---------------- Thu âm thanh ----------------

    @SuppressLint("MissingPermission")
    private fun startCapture(): Boolean {
        val p = projection ?: return false
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, RATE * 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Bus.status("Không mở được bộ thu âm thanh. Kiểm tra quyền Micro.")
                rec.release()
                return false
            }
            recorder = rec
            rec.startRecording()
            captureThread = thread(name = "capture") { loop(rec) }
            true
        } catch (e: Exception) {
            Bus.status("Lỗi thu âm thanh: ${e.message}")
            false
        }
    }

    private fun rms(f: ShortArray): Double {
        var s = 0.0
        for (v in f) s += v.toDouble() * v
        return sqrt(s / f.size)
    }

    /** Gom âm thanh, cắt ở chỗ ngắt câu (lặng ~0,3 giây) hoặc tối đa 8 giây. */
    private fun loop(rec: AudioRecord) {
        val frames = ArrayList<ShortArray>()
        var startShot: ByteArray? = null
        var voiced = 0
        var quietRun = 0
        var silentFor = 0
        while (running && !stopping) {
            val f = ShortArray(FRAME)
            var n = 0
            while (n < FRAME && running) {
                val r = rec.read(f, n, FRAME - n)
                if (r < 0) { if (running) Bus.status("Bộ thu âm thanh bị ngắt (mã $r)."); return }
                n += r
            }
            if (n < FRAME) return

            val level = rms(f)
            val loud = level > 250
            if (loud && !heardAudio) {
                heardAudio = true
                Bus.status("Đã nghe thấy âm thanh phim — đang dịch…")
            }
            if (!loud) {
                silentFor++
                if (silentFor == 150 && !heardAudio) {
                    Bus.status("Chưa nghe thấy tiếng. Phim đã phát chưa? Một số web phim chặn thu âm thanh.")
                }
            } else silentFor = 0

            // Bỏ phần im lặng ở đầu
            if (frames.isEmpty() && !loud) continue

            if (frames.isEmpty()) startShot = shot()
            frames.add(f)
            if (loud) { voiced++; quietRun = 0 } else quietRun++

            val dur = frames.size // đơn vị 100 ms
            // Đoạn ~3,5 s trở lên để không vượt giới hạn lượt dịch miễn phí (~15 lượt/phút)
            val cut = (dur >= 35 && quietRun >= 3) ||   // ≥3,5 s và vừa ngắt câu
                      (dur >= 15 && quietRun >= 8) ||   // câu ngắn rồi lặng hẳn
                      dur >= 80                          // tối đa 8 s
            if (cut) {
                if (voiced >= 4) {
                    val keep = frames.size - (quietRun - 2).coerceAtLeast(0)
                    val endShot = shot()
                    val shots = listOfNotNull(startShot, endShot.takeIf { it !== startShot })
                    submit(merge(frames, keep), shots)
                }
                frames.clear(); voiced = 0; quietRun = 0; startShot = null
            }
        }
    }

    /** Chụp một khung hình nếu đang bật "cho AI xem hình". */
    private fun shot(): ByteArray? {
        val v = vision ?: return null
        if (!prefs.getBoolean(MainActivity.K_VISION, true)) return null
        val j = v.grab()
        if (j == null && v.blackFrames >= 3 && !warnedBlack) {
            warnedBlack = true
            Bus.status("Web phim này chặn chụp hình (màn đen) — app chỉ dịch theo tiếng.")
        }
        return j
    }

    private fun merge(frames: List<ShortArray>, count: Int): ShortArray {
        val out = ShortArray(count * FRAME)
        for (i in 0 until count) System.arraycopy(frames[i], 0, out, i * FRAME, FRAME)
        return out
    }

    // ---------------- Dịch ----------------

    private fun submit(pcm: ShortArray, shots: List<ByteArray>) {
        if (pending.get() >= 2) {
            Bus.status("Mạng chậm — bỏ qua một đoạn để theo kịp phim.")
            return
        }
        pending.incrementAndGet()
        try {
            worker.execute {
                try { translate(pcm, shots) } finally { pending.decrementAndGet() }
            }
        } catch (_: Exception) {
            pending.decrementAndGet()
        }
    }

    private fun translate(pcm: ShortArray, shots: List<ByteArray>) {
        if (!running) return
        val wait = backoffUntil - System.currentTimeMillis()
        if (wait > 0) {
            // Đang bị giới hạn lượt: bỏ đoạn này cho đỡ trễ
            return
        }
        val key = prefs.getString(MainActivity.K_KEY, "") ?: ""
        val model = prefs.getString(MainActivity.K_MODEL, MainActivity.DEFAULT_MODEL) ?: MainActivity.DEFAULT_MODEL
        val ctx = synchronized(recent) { recent.joinToString("\n") }

        when (val r = Gemini.translate(key, model, Wav.encode(pcm, RATE), shots, ctx, MainActivity.extraInstructions(prefs))) {
            is Gemini.Res.Ok -> {
                val text = r.text
                if (text.isBlank() || text == "\"\"") return
                synchronized(recent) {
                    // Nhớ cả lời gốc lẫn bản dịch để AI giữ xưng hô nhất quán
                    recent.addLast(if (r.src.isNotBlank()) "${r.src} => $text" else text)
                    while (recent.size > 8) recent.removeFirst()
                }
                Bus.line(r.lang, text)
                if (prefs.getBoolean(MainActivity.K_SUB, true) && Settings.canDrawOverlays(this)) {
                    val ov = overlay ?: Overlay(this).also { overlay = it }
                    ov.show(text, prefs.getInt(MainActivity.K_FONT, 20).toFloat())
                }
                if (prefs.getBoolean(MainActivity.K_SPEAK, true)) speak(text)
            }
            is Gemini.Res.Err -> handleError(r)
        }
    }

    private fun handleError(e: Gemini.Res.Err) {
        when (e.code) {
            429 -> {
                val sec = if (e.retrySec > 0) e.retrySec else 20
                backoffUntil = System.currentTimeMillis() + sec * 1000L
                Bus.status("Hết lượt dịch miễn phí tạm thời — chờ khoảng $sec giây. " +
                    if (e.msg.contains("day", true) || e.msg.contains("PerDay", true)) "(Có thể đã hết lượt trong ngày.)" else "")
            }
            400, 401, 403 -> {
                if (e.msg.contains("API key", true) || e.code != 400) {
                    Bus.status("API key không đúng hoặc chưa bật. Kiểm tra lại key.")
                } else Bus.status("Lỗi yêu cầu: ${e.msg.take(150)}")
            }
            404 -> Bus.status("Không có mô hình AI tên \"${prefs.getString(MainActivity.K_MODEL, "")}\". Bấm nút \"Nhanh, nhiều lượt\" trong app.")
            -1 -> Bus.status(e.msg.take(150))
            else -> Bus.status("Lỗi máy chủ (${e.code}): ${e.msg.take(120)}")
        }
    }

    // ---------------- Dừng ----------------

    override fun onDestroy() {
        stopping = true
        running = false
        try { recorder?.stop() } catch (_: Exception) { }
        try { captureThread?.join(500) } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        vision?.stop(); vision = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        overlay?.remove(); overlay = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        worker.shutdownNow()
        Bus.status("Đã dừng.")
        super.onDestroy()
    }
}

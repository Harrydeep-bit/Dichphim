package com.quang.dichphim

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Kênh nhỏ để dịch vụ chạy nền báo cho màn hình chính. */
object Bus {
    interface Listener {
        fun onLine(line: String)
        fun onStatus(status: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile var lastStatus: String = "Chưa chạy"
    private val history = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())

    fun line(lang: String, text: String) {
        val l = if (lang.isBlank()) text else "[$lang] $text"
        synchronized(history) {
            history.add(l)
            if (history.size > 300) history.removeAt(0)
        }
        ui.post { listener?.onLine(l) }
    }

    fun status(s: String) {
        lastStatus = s
        ui.post { listener?.onStatus(s) }
    }

    fun allLines(): List<String> = synchronized(history) { ArrayList(history) }

    fun clear() = synchronized(history) { history.clear() }
}

object Wav {
    /** Đóng gói PCM 16-bit mono thành file WAV. */
    fun encode(pcm: ShortArray, rate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)          // PCM
        header.putShort(1)          // mono
        header.putInt(rate)
        header.putInt(rate * 2)     // byte rate
        header.putShort(2)          // block align
        header.putShort(16)         // bits
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)

        val data = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        data.asShortBuffer().put(pcm)
        val out = ByteArrayOutputStream(44 + dataLen)
        out.write(header.array())
        out.write(data.array())
        return out.toByteArray()
    }
}

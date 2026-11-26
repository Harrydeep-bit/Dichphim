package com.quang.dichphim

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gửi một đoạn âm thanh lên Gemini: tự nhận ra ngôn ngữ và dịch sang tiếng Việt. */
object Gemini {

    sealed class Res {
        data class Ok(val lang: String, val text: String) : Res()
        data class Err(val code: Int, val msg: String, val retrySec: Int = 0) : Res()
    }

    private const val PROMPT = """Bạn là người dịch phụ đề phim chuyên nghiệp.
Đoạn âm thanh đính kèm được cắt từ một bộ phim hoặc video đang phát (dài vài giây).
Hãy nghe lời thoại — có thể là BẤT KỲ ngôn ngữ nào (Anh, Trung, Hàn, Nhật, Thái, Pháp, Tây Ban Nha, Đức, Nga, Ấn, Indonesia...) — rồi dịch sang tiếng Việt tự nhiên, ngắn gọn, đúng giọng nhân vật như phụ đề phim.
Quy tắc:
- Nếu lời thoại đã là tiếng Việt thì chép lại nguyên văn.
- Nếu không có lời nói rõ ràng (chỉ có nhạc, tiếng động, tiếng thở, im lặng) thì để "vi" là chuỗi rỗng.
- Không giải thích, không thêm chú thích, không bịa những chỗ không nghe được.
- Lời bài hát trong phim cũng dịch nếu nghe rõ.
Chỉ trả về JSON đúng dạng: {"lang": "tên ngôn ngữ gốc viết bằng tiếng Việt", "vi": "bản dịch tiếng Việt"}"""

    fun translate(apiKey: String, modelIn: String, wav: ByteArray, context: String): Res {
        val model = modelIn.trim().removePrefix("models/").ifEmpty { MainActivity.DEFAULT_MODEL }
        val tryThinking = model !in noThinking
        var res = call(apiKey, model, wav, context, withThinking = tryThinking)
        // Mô hình không nhận tham số "suy nghĩ ít" -> nhớ lại và gửi không có nó.
        if (tryThinking && res is Res.Err && res.code == 400) {
            noThinking.add(model)
            res = call(apiKey, model, wav, context, withThinking = false)
        }
        return res
    }

    private val noThinking = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun call(apiKey: String, model: String, wav: ByteArray, context: String, withThinking: Boolean): Res {
        val prompt = if (context.isBlank()) PROMPT else
            "$PROMPT\n\nNgữ cảnh — các câu phụ đề vừa hiện trước đó (chỉ để hiểu mạch phim, KHÔNG dịch lại):\n$context"

        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))

        val gen = JSONObject()
            .put("temperature", 0.3)
            .put("responseMimeType", "application/json")
        // Suy nghĩ ít nhất để dịch nhanh, ra phụ đề kịp lời thoại
        if (withThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))

        val safety = JSONArray()
        for (c in listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")) {
            safety.put(JSONObject().put("category", c).put("threshold", "BLOCK_NONE"))
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
            .put("safetySettings", safety)

        val conn: HttpURLConnection
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey.trim())
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            return Res.Err(-1, "Không kết nối được mạng: ${e.message}")
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) parseOk(text) else parseErr(code, text)
        } catch (e: Exception) {
            Res.Err(-1, "Lỗi mạng: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseOk(raw: String): Res {
        val root = JSONObject(raw)
        val cands = root.optJSONArray("candidates") ?: return Res.Ok("", "")
        if (cands.length() == 0) return Res.Ok("", "")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return Res.Ok("", "")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        var t = sb.toString().trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        return try {
            val j = JSONObject(t)
            Res.Ok(j.optString("lang", "").trim(), j.optString("vi", "").trim())
        } catch (e: Exception) {
            // Mô hình không trả JSON -> dùng nguyên văn
            Res.Ok("", t)
        }
    }

    private fun parseErr(code: Int, raw: String): Res {
        var msg = raw.take(300)
        var retry = 0
        try {
            val err = JSONObject(raw).getJSONObject("error")
            msg = err.optString("message", msg)
            val details = err.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val d = details.getJSONObject(i)
                    val delay = d.optString("retryDelay", "")
                    if (delay.endsWith("s")) retry = delay.removeSuffix("s").toDoubleOrNull()?.toInt() ?: 0
                }
            }
        } catch (_: Exception) { }
        return Res.Err(code, msg, retry)
    }
}

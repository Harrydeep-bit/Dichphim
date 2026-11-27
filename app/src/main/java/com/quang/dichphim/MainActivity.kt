package com.quang.dichphim

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity(), Bus.Listener {

    companion object {
        const val PREFS = "dichphim"
        const val K_KEY = "apiKey"
        const val K_MODEL = "model"
        const val K_SUB = "showSub"
        const val K_SPEAK = "speak"
        const val K_FONT = "font"
        const val K_RATE = "rate"
        const val K_PRONOUN = "pronoun"
        const val K_NOTES = "notes"
        const val K_VISION = "vision"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val BETTER_MODEL = "gemini-3.6-flash"
        private const val REQ_CAPTURE = 10
        private const val REQ_PERMS = 11

        /** Tên hiển thị và yêu cầu gửi kèm cho AI, theo thứ tự trong danh sách chọn. */
        val PRONOUN_STYLES = listOf(
            "Tự động (AI nghe giọng để chọn)" to "",
            "Trung tính: tôi – bạn" to
                "Luôn xưng hô trung tính \"tôi – bạn\" (ngôi thứ ba: anh ấy / cô ấy / họ), trừ khi lời gốc gọi rõ quan hệ gia đình.",
            "Anh – em" to
                "Các nhân vật xưng hô \"anh – em\" với nhau (người lớn tuổi hơn hoặc nam là \"anh\"), trừ khi lời gốc gọi rõ quan hệ khác.",
            "Cậu – tớ (bạn bè, học sinh)" to
                "Các nhân vật là bạn bè cùng trang lứa, xưng hô \"cậu – tớ\" hoặc \"mình – cậu\".",
            "Ta – ngươi (phim cổ trang, kiếm hiệp)" to
                "Phim cổ trang: dùng lối xưng hô cổ như \"ta – ngươi\", \"huynh – đệ\", \"tỷ – muội\", \"sư phụ – đồ nhi\", \"bệ hạ\", \"thần\"... cho hợp ngữ cảnh."
        )

        fun extraInstructions(p: android.content.SharedPreferences): String {
            val sb = StringBuilder()
            val i = p.getInt(K_PRONOUN, 0).coerceIn(0, PRONOUN_STYLES.size - 1)
            val rule = PRONOUN_STYLES[i].second
            if (rule.isNotEmpty()) sb.append("- Xưng hô: ").append(rule).append('\n')
            val notes = p.getString(K_NOTES, "")?.trim().orEmpty()
            if (notes.isNotEmpty()) sb.append("- Thông tin về phim do người xem cung cấp (dùng để chọn xưng hô và dịch tên cho đúng): ")
                .append(notes).append('\n')
            return sb.toString()
        }
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var subBox: CheckBox
    private lateinit var speakBox: CheckBox
    private lateinit var visionBox: CheckBox
    private lateinit var fontLabel: TextView
    private lateinit var rateLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var historyTv: TextView
    private lateinit var historyScroll: ScrollView
    private lateinit var guideTv: TextView
    private lateinit var notesEdit: EditText

    private var waitingOverlay = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Dịch Phim"
        buildUi()
    }

    // ---------------- Giao diện ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
        }

        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settingsScroll = ScrollView(this).apply { addView(settings) }

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        }

        // API key
        settings.addView(label("Mã API Gemini (miễn phí)"))
        keyEdit = EditText(this).apply {
            hint = "Dán mã API vào đây (bắt đầu bằng AIza...)"
            setText(prefs.getString(K_KEY, ""))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        settings.addView(keyEdit)
        settings.addView(Button(this).apply {
            text = "Lấy mã API miễn phí (Google AI Studio)"
            isAllCaps = false
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                catch (_: Exception) { toast("Không mở được trình duyệt") }
            }
        })

        // Mô hình
        settings.addView(label("Mô hình AI"))
        modelEdit = EditText(this).apply {
            setText(prefs.getString(K_MODEL, DEFAULT_MODEL))
            setSingleLine()
            textSize = 14f
        }
        settings.addView(modelEdit)
        settings.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Nhanh, nhiều lượt"; isAllCaps = false
                setOnClickListener { modelEdit.setText(DEFAULT_MODEL); saveSettings() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Dịch hay hơn"; isAllCaps = false
                setOnClickListener { modelEdit.setText(BETTER_MODEL); saveSettings() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })

        // Xưng hô
        settings.addView(label("Cách xưng hô trong bản dịch"))
        settings.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                PRONOUN_STYLES.map { it.first })
            setSelection(prefs.getInt(K_PRONOUN, 0).coerceIn(0, PRONOUN_STYLES.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    prefs.edit().putInt(K_PRONOUN, pos).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })

        settings.addView(label("Ghi chú về phim (không bắt buộc)"))
        notesEdit = EditText(this).apply {
            hint = "VD: Phim Hàn. Minho và Jisu là hai anh em ruột, Minho là anh. Bà Kim là mẹ của họ."
            setText(prefs.getString(K_NOTES, ""))
            minLines = 2
            maxLines = 5
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        settings.addView(notesEdit)
        settings.addView(TextView(this).apply {
            text = "Ghi rõ ai là gì của ai thì AI xưng hô đúng hơn nhiều. Đổi phim thì nhớ xoá ghi chú cũ."
            textSize = 12f
            setTextColor(Color.parseColor("#777777"))
        })

        // Chế độ
        settings.addView(label("Cách hiển thị"))
        subBox = CheckBox(this).apply {
            text = "Hiện phụ đề nổi trên màn hình"
            isChecked = prefs.getBoolean(K_SUB, true)
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        speakBox = CheckBox(this).apply {
            text = "Đọc to bản dịch tiếng Việt"
            isChecked = prefs.getBoolean(K_SPEAK, true)
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        visionBox = CheckBox(this).apply {
            text = "Cho AI xem hình phim (xưng hô đúng hơn, tốn mạng hơn)"
            isChecked = prefs.getBoolean(K_VISION, true)
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        settings.addView(subBox)
        settings.addView(speakBox)
        settings.addView(visionBox)

        fontLabel = TextView(this)
        settings.addView(stepper(fontLabel,
            { prefs.edit().putInt(K_FONT, (prefs.getInt(K_FONT, 20) - 2).coerceAtLeast(12)).apply(); refreshSteppers() },
            { prefs.edit().putInt(K_FONT, (prefs.getInt(K_FONT, 20) + 2).coerceAtMost(40)).apply(); refreshSteppers() }))
        rateLabel = TextView(this)
        settings.addView(stepper(rateLabel,
            { prefs.edit().putFloat(K_RATE, (prefs.getFloat(K_RATE, 1.15f) - 0.1f).coerceAtLeast(0.6f)).apply(); refreshSteppers() },
            { prefs.edit().putFloat(K_RATE, (prefs.getFloat(K_RATE, 1.15f) + 0.1f).coerceAtMost(2.0f)).apply(); refreshSteppers() }))
        refreshSteppers()

        // Hướng dẫn
        guideTv = TextView(this).apply {
            text = GUIDE
            textSize = 13f
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        settings.addView(Button(this).apply {
            text = "Hướng dẫn / Khi không có tiếng"; isAllCaps = false
            setOnClickListener {
                guideTv.visibility = if (guideTv.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        })
        settings.addView(guideTv)

        root.addView(settingsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.1f))

        // Nút chính
        startBtn = Button(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { if (CaptureService.running) stopCapture() else beginStart() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(60)).apply { topMargin = dp(8) })

        statusTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(statusTv)

        // Lịch sử phụ đề
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Phụ đề đã dịch"; setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Xoá"; isAllCaps = false
                setOnClickListener { Bus.clear(); historyTv.text = "" }
            })
        })
        historyTv = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextIsSelectable(true)
        }
        historyScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#202124"))
            addView(historyTv)
        }
        root.addView(historyScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun stepper(label: TextView, minus: () -> Unit, plus: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            label.textSize = 15f
            addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply { text = "−"; setOnClickListener { minus() } },
                LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
            addView(Button(this@MainActivity).apply { text = "+"; setOnClickListener { plus() } },
                LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        }

    private fun refreshSteppers() {
        fontLabel.text = "Cỡ chữ phụ đề: ${prefs.getInt(K_FONT, 20)}"
        rateLabel.text = "Tốc độ đọc: " + String.format("%.1f", prefs.getFloat(K_RATE, 1.15f)) + "x"
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(K_KEY, keyEdit.text.toString().trim())
            .putString(K_MODEL, modelEdit.text.toString().trim().ifEmpty { DEFAULT_MODEL })
            .putBoolean(K_SUB, subBox.isChecked)
            .putBoolean(K_SPEAK, speakBox.isChecked)
            .putBoolean(K_VISION, visionBox.isChecked)
            .putString(K_NOTES, notesEdit.text.toString().trim())
            .apply()
    }

    private fun refreshButton() {
        if (CaptureService.running) {
            startBtn.text = "DỪNG DỊCH"
            startBtn.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            startBtn.text = "BẮT ĐẦU DỊCH"
            startBtn.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
        startBtn.setTextColor(Color.WHITE)
    }

    // ---------------- Bắt đầu / dừng ----------------

    private fun beginStart() {
        saveSettings()
        if (keyEdit.text.toString().trim().length < 20) {
            toast("Anh dán mã API Gemini vào trước đã nhé")
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMS)
            return
        }
        checkOverlayThenCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Cần quyền Micro để nghe được âm thanh phim")
            return
        }
        checkOverlayThenCapture()
    }

    private fun checkOverlayThenCapture() {
        if (subBox.isChecked && !Settings.canDrawOverlays(this)) {
            waitingOverlay = true
            toast("Bật \"Hiển thị trên ứng dụng khác\" cho Dịch Phim rồi quay lại")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        toast("Chọn \"Toàn bộ màn hình\" rồi bấm Bắt đầu")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Anh chưa cho phép thu âm thanh")
            return
        }
        val svc = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(svc)
        statusTv.text = "Đang khởi động…"
        startBtn.postDelayed({ refreshButton() }, 600)
        toast("Xong! Giờ mở Chrome và phát phim")
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
        startBtn.postDelayed({ refreshButton() }, 300)
    }

    // ---------------- Vòng đời ----------------

    override fun onResume() {
        super.onResume()
        Bus.listener = this
        refreshButton()
        statusTv.text = Bus.lastStatus
        historyTv.text = Bus.allLines().joinToString("\n\n")
        scrollToEnd()
        if (waitingOverlay) {
            waitingOverlay = false
            if (Settings.canDrawOverlays(this)) requestCapture()
            else toast("Chưa có quyền hiện phụ đề nổi — vẫn có thể chỉ đọc to hoặc bỏ chọn phụ đề nổi")
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        if (Bus.listener === this) Bus.listener = null
    }

    override fun onLine(line: String) {
        if (historyTv.text.isEmpty()) historyTv.text = line else historyTv.append("\n\n$line")
        scrollToEnd()
    }

    override fun onStatus(status: String) {
        statusTv.text = status
        refreshButton()
    }

    private fun scrollToEnd() {
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private val GUIDE = """
CÁCH DÙNG
1. Dán mã API Gemini (bấm nút "Lấy mã API miễn phí", đăng nhập Gmail, bấm Create API key, sao chép).
2. Bấm BẮT ĐẦU DỊCH → cho phép Micro, Thông báo, "Hiển thị trên ứng dụng khác".
3. Khi máy hỏi chia sẻ màn hình: chọn "Toàn bộ màn hình" → Bắt đầu. (App chỉ lấy ÂM THANH, không quay hình.)
4. Mở Chrome, vào web phim và phát. Phụ đề tiếng Việt hiện ở dưới màn hình sau khoảng 2–4 giây, và được đọc to.
5. Kéo dòng phụ đề lên/xuống để đổi chỗ. Chạm nhẹ để ẩn tạm.
6. Dừng: bấm DỪNG DỊCH, hoặc kéo thanh thông báo xuống bấm "Dừng".

NGÔN NGỮ
App tự nhận ra mọi thứ tiếng (Anh, Trung, Hàn, Nhật, Thái, Pháp...), không cần chọn.

MÁY XIAOMI / REDMI (quan trọng)
• Cài đặt → Ứng dụng → Quản lý ứng dụng → Dịch Phim → Quyền khác → bật "Hiển thị cửa sổ bật lên khi chạy nền" và "Hiển thị trên ứng dụng khác".
• Cũng trong đó: Tiết kiệm pin → chọn "Không hạn chế". Bật "Tự khởi chạy".

XƯNG HÔ SAI (VD "anh em" thành "mẹ con")?
• Bật "Cho AI xem hình phim": app chụp 1–2 khung hình mỗi câu thoại để AI thấy ai đang nói. (Bật/tắt có hiệu lực từ lần bấm Bắt đầu sau. Hình làm tốn thêm dung lượng mạng, nên xem bằng Wi-Fi.)
• Chọn kiểu xưng hô hợp với phim ở mục "Cách xưng hô".
• Ghi vào "Ghi chú về phim" ai là gì của ai, VD: "A và B là hai anh em trai".
• Bấm "Dịch hay hơn" để dùng mô hình AI giỏi hơn.

KHÔNG CÓ GIỌNG ĐỌC TIẾNG VIỆT?
Cài "Dịch vụ giọng nói của Google" (Speech Services by Google) từ CH Play, rồi vào Cài đặt → Cài đặt bổ sung → Hỗ trợ tiếp cận → Chuyển văn bản thành giọng nói → chọn Google, ngôn ngữ Tiếng Việt.

KHÔNG RA PHỤ ĐỀ?
• Một số web phim có bản quyền (Netflix, FPT Play...) chặn thu âm thanh — app không nghe được.
• Video đang tắt tiếng thì app cũng không nghe được.
• Báo "hết lượt": gói miễn phí giới hạn số lần dịch mỗi phút/ngày. Chờ một lát, hoặc dùng nút "Nhanh, nhiều lượt".
• Mạng yếu sẽ trễ hơn.
""".trimIndent()
}

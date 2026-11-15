package com.lifi.messenger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Glowing "flashlight" indicator that mirrors the real torch state. */
class LampView(ctx: Context) : View(ctx) {
    var on = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        if (on) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(Color.WHITE, 0xFF22D3EE.toInt(), 0x0022D3EE),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, p)
            p.shader = null
        } else {
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = 0xFF1E293B.toInt()
            c.drawCircle(cx, cy, r * 0.45f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = 0xFF22D3EE.toInt()
        c.drawCircle(cx, cy, r * 0.45f, p)
    }
}

class MainActivity : Activity() {

    private val BG = 0xFF0B1220.toInt()
    private val CARD = 0xFF131C2F.toInt()
    private val LINE = 0xFF243049.toInt()
    private val TEXT = 0xFFE6EDF7.toInt()
    private val MUTED = 0xFF8FA0BD.toInt()
    private val ACCENT = 0xFF22D3EE.toInt()
    private val OK = 0xFF34D399.toInt()
    private val BAD = 0xFFF87171.toInt()
    private val WARN = 0xFFFBBF24.toInt()

    private val speedLabels = listOf("Fast 100 ms", "Normal 150 ms", "Safe 250 ms")
    private val speedValues = listOf(100, 150, 250)

    private lateinit var tx: TorchTransmitter
    private lateinit var cm: CameraManager
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var torchChip: TextView
    private lateinit var infoOriginal: TextView
    private lateinit var infoBinary: TextView
    private lateinit var infoChars: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoBits: TextView
    private lateinit var liveBits: TextView
    private lateinit var progress: ProgressBar
    private lateinit var lamp: LampView
    private lateinit var btnSend: Button
    private lateinit var btnStop: Button
    private lateinit var btnAgain: Button
    private val radios = ArrayList<RadioButton>()
    private var lastSent: String? = null
    private val history = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == tx.cameraId && !tx.running) setChip("Torch busy (camera in use)", WARN)
        }
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == tx.cameraId && !tx.running) setChip("Torch ready", OK)
        }
    }

    // ---------------------------------------------------------------- UI helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 18, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun label(t: String, size: Float = 12f, color: Int = MUTED, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(t: String, bg: Int, fg: Int, outlined: Boolean = false) = Button(this).apply {
        text = t; isAllCaps = true; setTextColor(fg); textSize = 14f
        stateListAnimator = null
        background = if (outlined) rounded(Color.TRANSPARENT, 14, bg) else rounded(bg, 14)
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) }
    }

    private fun enable(b: Button, on: Boolean) { b.isEnabled = on; b.alpha = if (on) 1f else 0.4f }

    private fun setStatus(t: String, color: Int) { status.text = t; status.setTextColor(color) }

    private fun setChip(t: String, color: Int) {
        torchChip.text = "\u25CF  $t"
        torchChip.setTextColor(color)
    }

    // ---------------------------------------------------------------- build screen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(col) })

        // Header
        lamp = LampView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)) }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(label("Li-Fi Messenger", 26f, TEXT, true))
            addView(label("Light-Based Wireless Communication", 13f, ACCENT))
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(lamp); addView(titles)
        })
        torchChip = label("", 13f, OK).apply {
            background = rounded(CARD, 20, LINE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) }
        }
        col.addView(torchChip)

        // Message
        val msgCard = card()
        input = EditText(this).apply {
            hint = "Type your message here..."
            setHintTextColor(MUTED); setTextColor(TEXT); textSize = 17f
            minLines = 3; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(LiFiProtocol.MAX_MSG))
            background = rounded(0xFF0E1627.toInt(), 12, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        counter = label("", 12f)
        msgCard.addView(label("MESSAGE", 11f, ACCENT, true))
        msgCard.addView(input)
        msgCard.addView(counter)
        col.addView(msgCard)

        // Speed
        val speedCard = card()
        speedCard.addView(label("SPEED (time of one light bit)", 11f, ACCENT, true))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        speedLabels.forEachIndexed { i, s ->
            val rb = RadioButton(this).apply {
                text = s; setTextColor(TEXT); textSize = 12f; id = View.generateViewId(); isChecked = (i == 1)
                layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
            }
            radios.add(rb); group.addView(rb)
        }
        group.setOnCheckedChangeListener { _, _ -> refreshEstimate() }
        speedCard.addView(group)
        speedCard.addView(label("Use Safe if the LCD shows errors. The Arduino adapts automatically.", 11f))
        col.addView(speedCard)

        // Buttons
        btnSend = button("Transmit", ACCENT, 0xFF04202B.toInt()).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(16) }
            textSize = 18f
        }
        btnStop = button("Stop", BAD, Color.WHITE)
        btnAgain = button("Send again", 0xFF334155.toInt(), TEXT)
        val btnClear = button("Clear", 0xFF334155.toInt(), TEXT)
        val btnHistory = button("History", ACCENT, ACCENT, outlined = true)
        col.addView(btnSend)
        col.addView(LinearLayout(this).apply { addView(btnStop); addView(btnAgain); addView(btnClear) })
        col.addView(LinearLayout(this).apply { addView(btnHistory) })

        // Transmission info
        val info = card()
        status = label("Ready", 18f, OK, true)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(10) }
            progressDrawable.setTint(ACCENT)
        }
        infoChars = label("Characters: 0", 13f, TEXT)
        infoTime = label("Estimated time: 0 s", 13f, TEXT)
        infoBits = label("Bits sent: 0 / 0", 13f, TEXT)
        liveBits = label("", 14f, ACCENT).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, dp(4)) }
        infoOriginal = label("Original message: -", 13f, TEXT)
        infoBinary = label("Binary: -", 12f, MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        info.addView(label("TRANSMISSION", 11f, ACCENT, true))
        info.addView(status); info.addView(progress)
        info.addView(infoChars); info.addView(infoTime); info.addView(infoBits)
        info.addView(liveBits); info.addView(infoOriginal); info.addView(infoBinary)
        col.addView(info)

        // Behaviour
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshEstimate() }
        })
        btnSend.setOnClickListener { transmit(input.text.toString()) }
        btnAgain.setOnClickListener { lastSent?.let { transmit(it) } ?: toast("Nothing sent yet") }
        btnStop.setOnClickListener { if (tx.running) { tx.cancel(); setStatus("Stopping...", WARN) } }
        btnClear.setOnClickListener { if (!tx.running) input.setText("") }
        btnHistory.setOnClickListener { showHistory() }

        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        tx = TorchTransmitter(cm)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) || tx.cameraId == null) {
            setChip("No flashlight on this phone", BAD)
            setStatus("Cannot transmit: no flashlight", BAD)
            enable(btnSend, false); enable(btnAgain, false)
        } else {
            setChip("Torch ready", OK)
            cm.registerTorchCallback(torchCallback, ui)
        }
        enable(btnStop, false)
        loadHistory()
        refreshEstimate()
    }

    // ---------------------------------------------------------------- logic
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun bitMs(): Int = speedValues[radios.indexOfFirst { it.isChecked }.coerceAtLeast(0)]

    private fun refreshEstimate() {
        val n = LiFiProtocol.sanitize(input.text.toString()).length
        counter.text = "$n / ${LiFiProtocol.MAX_MSG} characters (letters, numbers, spaces, punctuation)"
        if (!tx.running) {
            infoChars.text = "Characters: $n"
            infoTime.text = "Estimated time: " + if (n == 0) "0 s" else fmt(LiFiProtocol.durationMs(n, bitMs()))
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
    }

    private fun transmit(raw: String) {
        if (tx.running) return
        val text = LiFiProtocol.sanitize(raw)
        if (text.isEmpty()) { toast("Type a message first"); return }
        if (!LiFiProtocol.isSupported(text)) {
            toast("Only letters, numbers, spaces and punctuation (plain ASCII) are supported by the LCD link")
            return
        }
        lastSent = text
        addHistory(text)
        val bits = LiFiProtocol.toBits(LiFiProtocol.buildPacket(text))
        val bm = bitMs()
        infoChars.text = "Characters: ${text.length}"
        infoTime.text = "Estimated time: " + fmt(LiFiProtocol.durationMs(text.length, bm))
        infoOriginal.text = "Original message: $text"
        infoBinary.text = "Binary: " + LiFiProtocol.binaryString(text)
        progress.max = bits.size; progress.progress = 0
        infoBits.text = "Bits sent: 0 / ${bits.size}"
        setStatus("Transmitting...", ACCENT)
        setChip("Torch transmitting", ACCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enable(btnSend, false); enable(btnAgain, false); enable(btnStop, true)

        tx.start(bits, bm,
            onBit = { i, on ->
                ui.post {
                    lamp.on = on
                    progress.progress = i + 1
                    infoBits.text = "Bits sent: ${i + 1} / ${bits.size}"
                    val from = maxOf(0, i - 27)
                    liveBits.text = (from..i).joinToString("") { if (bits[it]) "\u2588" else "\u2591" }
                }
            },
            onFinish = { done, err -> ui.post { finishTx(done, err) } })
    }

    private fun finishTx(done: Boolean, err: String?) {
        lamp.on = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enable(btnSend, tx.cameraId != null); enable(btnAgain, tx.cameraId != null); enable(btnStop, false)
        setChip("Torch ready", OK)
        when {
            done -> setStatus("Completed - check the LCD", OK)
            err == "CAMERA_PERMISSION" -> {
                setStatus("Failed: camera permission needed", BAD)
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                toast("Allow the camera permission, then press TRANSMIT again")
            }
            err != null -> setStatus("Failed: $err", BAD)
            else -> setStatus("Stopped - torch OFF", WARN)
        }
    }

    // ---------------------------------------------------------------- history
    private fun prefs() = getSharedPreferences("lifi", Context.MODE_PRIVATE)

    private fun loadHistory() {
        history.clear()
        val s = prefs().getString("history", "") ?: ""
        if (s.isNotEmpty()) history.addAll(s.split('\u0001'))
    }

    private fun addHistory(t: String) {
        history.remove(t)
        history.add(0, t)
        while (history.size > 30) history.removeAt(history.size - 1)
        prefs().edit().putString("history", history.joinToString("\u0001")).apply()
    }

    private fun showHistory() {
        if (history.isEmpty()) { toast("No messages sent yet"); return }
        AlertDialog.Builder(this)
            .setTitle("History - tap a message to reuse it")
            .setItems(history.toTypedArray()) { _, which ->
                input.setText(history[which]); input.setSelection(input.text.length)
                toast("Loaded. Press TRANSMIT to send it again")
            }
            .setNeutralButton("Clear history") { _, _ ->
                history.clear(); prefs().edit().remove("history").apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onStop() {                     // app interrupted / screen locked -> torch OFF
        if (tx.running) tx.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (tx.running) tx.cancel()
        try { cm.unregisterTorchCallback(torchCallback) } catch (_: Exception) { }
        super.onDestroy()
    }
}

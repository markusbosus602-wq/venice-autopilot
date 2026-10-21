@file:Suppress("DEPRECATION", "unused", "EXPERIMENTAL_API_USAGE", "OPT_IN_USAGE", "NAME_SHADOWING")

package ai.venice.autopilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ==================== Настройки ====================

object P {
    const val F = "autopilot"
    const val API = "api"
    const val BASE = "base"
    const val MODEL = "model"
    const val GOAL = "goal"
    const val IVL = "ivl"
    const val DIM = "dim"
    const val KEEP = "keep"
    const val LAST = "last"
    const val ZONE = "zone"
    const val DEF_BASE = "https://api.venice.ai/api/v1"
    const val DEF_MODEL = "qwen3-vl-235b-a22b"
    fun sp(c: Context) = c.getSharedPreferences(F, Context.MODE_PRIVATE)
}

object C {
    const val MODE_AI = 0
    const val MODE_MACRO = 1
    const val MODE_HYBRID = 2
    const val MODE_RECORD = 3
    const val MODE_SPEED = 4
    const val ZONE_ALL = 0
    const val ZONE_TOP = 1
    const val ZONE_BOTTOM = 2
    const val START = "ai.venice.autopilot.START"
    const val STOP = "ai.venice.autopilot.STOP"
}

object Mem {
    private fun nk(p: String) = "notes_$p"
    fun notes(c: Context, p: String): List<String> {
        val r = P.sp(c).getString(nk(p), null) ?: return emptyList()
        return try {
            val a = JSONArray(r)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList<String>()
        }
    }
    fun add(c: Context, p: String, list: List<String>) {
        if (list.isEmpty()) return
        val cur = notes(c, p).toMutableList()
        for (t in list) {
            val s = t.trim().replace("\n", " ").take(140)
            if (s.length < 4) continue
            if (cur.none { it.equals(s, true) }) cur.add(s)
        }
        while (cur.size > 22) cur.removeAt(0)
        val a = JSONArray()
        cur.forEach { a.put(it) }
        P.sp(c).edit().putString(nk(p), a.toString()).apply()
    }
    fun goal(c: Context, p: String) = P.sp(c).getString("goal_$p", "") ?: ""
    fun saveGoal(c: Context, p: String, g: String) { P.sp(c).edit().putString("goal_$p", g).apply() }
    fun clear(c: Context, p: String) { P.sp(c).edit().remove(nk(p)).apply() }
}

object Mac {
    data class T(val x: Int, val y: Int, val d: Int, val l: Boolean)
    fun save(c: Context, p: String, taps: List<T>) {
        val a = JSONArray()
        taps.forEach { a.put(JSONObject().put("x", it.x).put("y", it.y).put("d", it.d).put("l", it.l)) }
        P.sp(c).edit().putString("macro_$p", a.toString()).apply()
    }
    fun load(c: Context, p: String): List<T> {
        val s = P.sp(c).getString("macro_$p", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                T(o.optInt("x"), o.optInt("y"), o.optInt("d", 400), o.optBoolean("l"))
            }
        } catch (e: Exception) {
            emptyList<T>()
        }
    }
}

// ==================== Сетка координат ====================

object Gr {
    private var cache: Bitmap? = null
    private var cw = 0
    private var chh = 0

    fun draw(f: Bitmap): Bitmap {
        val o = grid(f.width, f.height)
        val out = f.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(o, 0f, 0f, null)
        return out
    }

    private fun grid(w: Int, h: Int): Bitmap {
        val c0 = cache
        if (c0 != null && cw == w && chh == h) return c0
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val line = Paint().apply { color = 0x4400E5FF; strokeWidth = 1f }
        val tx = Paint().apply {
            color = -0xFF1A01
            textSize = (w / 26f).coerceAtLeast(11f)
            isAntiAlias = true
            isFakeBoldText = true
        }
        val bg = Paint().apply { color = -0x66666667 }
        var x = 100
        while (x < w) { c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), line); x += 100 }
        var y = 100
        while (y < h) { c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), line); y += 100 }
        x = 200
        while (x < w) { lab(c, tx, bg, x.toString(), x.toFloat() + 3f, tx.textSize + 2f); x += 200 }
        y = 200
        while (y < h) { lab(c, tx, bg, y.toString(), 3f, y.toFloat() - 3f); y += 200 }
        cache = b
        cw = w
        chh = h
        return b
    }

    private fun lab(c: Canvas, p: Paint, bg: Paint, s: String, x: Float, y: Float) {
        val w = p.measureText(s)
        c.drawRect(x - 3f, y - p.textSize, x + w + 3f, y + 3f, bg)
        c.drawText(s, x, y, p)
    }

    fun cross(f: Bitmap, x: Int, y: Int): Bitmap {
        val out = f.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val p = Paint().apply { color = Color.RED; strokeWidth = 2f; isAntiAlias = true }
        val r = (out.width / 40f).coerceAtLeast(12f)
        c.drawLine(x - r, y.toFloat(), x + r, y.toFloat(), p)
        c.drawLine(x.toFloat(), y - r, x.toFloat(), y + r, p)
        return out
    }
}

fun Bitmap.toJpg(q: Int = 70): ByteArray {
    val os = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, q, os)
    return os.toByteArray()
}

// ==================== Тапы ====================

class TouchService : AccessibilityService() {

    companion object {
        @Volatile var inst: TouchService? = null
        @Volatile var foregroundPkg: String = ""
        val ok: Boolean get() = inst != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }

    override fun onUnbind(i: Intent?): Boolean {
        inst = null
        return super.onUnbind(i)
    }

    // Следим, какое приложение сейчас на экране — нужно для автопаузы
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName
        if (p != null && p.isNotEmpty()) foregroundPkg = p.toString()
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && e.action == KeyEvent.ACTION_DOWN) {
            AutoPilotService.inst?.togglePause()
            return true
        }
        return super.onKeyEvent(e)
    }

    fun tap(x: Float, y: Float, ms: Long = 45L) {
        send(stroke(Path().apply { moveTo(x, y) }, ms))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 250L) {
        send(stroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms))
    }

    fun doubleTap(x: Float, y: Float) {
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        send(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0L, 35L))
                .addStroke(GestureDescription.StrokeDescription(p2, 90L, 35L))
                .build()
        )
    }

    fun drag(x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        send(stroke(p, 700L, 200L))
    }

    private fun stroke(p: Path, ms: Long, d: Long = 0L): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, d, ms.coerceIn(20L, 5000L)))
            .build()

    private fun send(g: GestureDescription) {
        try { dispatchGesture(g, null, null) } catch (e: Throwable) {}
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
}

// ==================== Захват экрана ====================

class Capture(
    ctx: Context,
    private val code: Int,
    private val data: Intent,
    val w: Int,
    val h: Int,
    private val dens: Int
) {
    private val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private val handler = Handler(Looper.getMainLooper())
    private var vd: VirtualDisplay? = null
    private var proj: MediaProjection? = null
    private var reuse: Bitmap? = null

    init { recreate() }

    fun recreate() {
        try { vd?.release() } catch (e: Throwable) {}
        try { proj?.stop() } catch (e: Throwable) {}
        try { Thread.sleep(80) } catch (e: Throwable) {}
        proj = try { mpm.getMediaProjection(code, data) } catch (e: Throwable) { null }
        proj?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { try { vd?.release() } catch (e: Throwable) {} }
        }, handler)
        vd = proj?.createVirtualDisplay(
            "ap", w, h, dens,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )
    }

    fun frame(): Bitmap? {
        val img = try { reader.acquireLatestImage() } catch (e: Throwable) { null } ?: return null
        try {
            val pl = img.planes[0]
            val ps = pl.pixelStride
            val pad = pl.rowStride - ps * img.width
            val rw = img.width + pad / ps
            var b = reuse
            if (b == null || b.width != rw || b.height != img.height) {
                b = Bitmap.createBitmap(rw, img.height, Bitmap.Config.ARGB_8888)
                reuse = b
            }
            b.copyPixelsFromBuffer(pl.buffer)
            return if (rw == w) b else Bitmap.createBitmap(b, 0, 0, w, h)
        } catch (e: Throwable) {
            return null
        } finally {
            try { img.close() } catch (e: Throwable) {}
        }
    }

    fun release() {
        try { vd?.release() } catch (e: Throwable) {}
        try { reader.close() } catch (e: Throwable) {}
        try { proj?.stop() } catch (e: Throwable) {}
    }
}

// ==================== Мозг ====================

class Brain(private val base: String, private val key: String, private val model: String) {

    data class A(val t: String, val x: Int = 0, val y: Int = 0, val x2: Int = 0, val y2: Int = 0, val ms: Int = 0)

    data class S(
        val think: String = "",
        val acts: List<A> = emptyList(),
        val lvl: Boolean = false,
        val done: Boolean = false,
        val reason: String = "",
        val learn: List<String> = emptyList()
    )

    fun ask(sys: String, userText: String, imgs: List<ByteArray>, onThink: (String) -> Unit, onAct: (A) -> Unit): S {
        val root = JSONObject()
        root.put("model", model)
        root.put("temperature", 0.15)
        root.put("max_tokens", 700)
        root.put("stream", true)

        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", sys))

        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", userText))
        for (im in imgs) {
            val b64 = Base64.encodeToString(im, Base64.NO_WRAP)
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64))
            )
        }
        msgs.put(JSONObject().put("role", "user").put("content", content))
        root.put("messages", msgs)

        val parser = Parser(onThink, onAct)
        val c = open(base.trimEnd('/') + "/chat/completions", root.toString())
        val httpCode = c.responseCode
        if (httpCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw RuntimeException("HTTP $httpCode: ${err.take(300)}")
        }
        c.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val d = try {
                    JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("delta")?.optString("content").orEmpty()
                } catch (e: Exception) {
                    ""
                }
                if (d.isNotEmpty()) parser.feed(d)
            }
        }
        return parser.finish()
    }

    fun ping(): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("max_tokens", 20)
        root.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ответь: ok")))
        val c = open(base.trimEnd('/') + "/chat/completions", root.toString())
        val t = c.inputStream.bufferedReader().readText()
        return JSONObject(t).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun open(url: String, body: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c
    }

    private class Parser(val onThink: (String) -> Unit, val onAct: (A) -> Unit) {
        val sb = StringBuilder()
        var cur = -1
        var depth = 0
        var start = -1
        var thinkSent = false
        val out = ArrayList<A>()

        fun feed(chunk: String) {
            sb.append(chunk)
            scanThink()
            scanActs()
        }

        fun scanThink() {
            if (thinkSent) return
            val k = sb.indexOf("\"think\"")
            if (k < 0) return
            val a = sb.indexOf("\"", k + 7)
            if (a < 0) return
            val b = sb.indexOf("\"", a + 1)
            if (b < 0) return
            val s = sb.substring(a + 1, b)
            if (s.isNotBlank()) {
                thinkSent = true
                onThink(s)
            }
        }

        fun scanActs() {
            if (cur < 0) {
                val i = sb.indexOf("\"actions\"")
                if (i < 0) return
                val br = sb.indexOf('[', i)
                if (br < 0) return
                cur = br + 1
            }
            while (cur < sb.length) {
                val ch = sb[cur]
                if (depth == 0) {
                    if (ch == '{') {
                        depth = 1
                        start = cur
                    } else if (ch == ']') {
                        cur++
                        return
                    }
                } else {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val obj = sb.substring(start, cur + 1)
                            val a = parse(obj)
                            if (a != null) {
                                out.add(a)
                                onAct(a)
                            }
                            start = -1
                        }
                    } else if (ch == '"') {
                        cur = skip(cur)
                    }
                }
                cur++
            }
        }

        fun skip(from: Int): Int {
            var i = from + 1
            while (i < sb.length) {
                val c = sb[i]
                if (c == '\\') { i += 2; continue }
                if (c == '"') return i
                i++
            }
            return sb.length - 1
        }

        fun finish(): S {
            val raw = sb.toString()
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            var lvl = false
            var done = false
            var reason = ""
            var think = ""
            var learn = emptyList<String>()
            if (a in 0 until b) {
                try {
                    val j = JSONObject(raw.substring(a, b + 1))
                    think = j.optString("think", "")
                    lvl = j.optBoolean("levelDone", false)
                    done = j.optBoolean("done", false)
                    reason = j.optString("reason", "")
                    val arr = j.optJSONArray("learn")
                    if (arr != null) {
                        learn = (0 until arr.length()).map { arr.optString(it) }
                    }
                } catch (e: Exception) {}
            }
            return S(think, out, lvl, done, reason, learn)
        }

        fun parse(json: String): A? = try {
            val o = JSONObject(json)
            A(
                o.optString("type", "tap").lowercase(),
                o.optInt("x"), o.optInt("y"),
                o.optInt("x2"), o.optInt("y2"), o.optInt("ms")
            )
        } catch (e: Exception) {
            null
        }
    }
}

// ==================== Оверлеи ====================

class Ovl(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var dim: View? = null
    private var panel: View? = null
    private var label: TextView? = null
    private var rec: View? = null

    private fun base(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    fun showDim(bright: Float, keep: Boolean) {
        if (dim != null) return
        val v = View(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            keepScreenOn = keep
        }
        val lp = base().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            screenBrightness = bright.coerceIn(0.01f, 1f)
        }
        wm.addView(v, lp)
        dim = v
    }

    fun hideDim() {
        val d = dim
        if (d != null) try { wm.removeView(d) } catch (e: Throwable) {}
        dim = null
    }

    fun showPanel(onPause: () -> Unit, onStop: () -> Unit) {
        if (panel != null) return
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(-0x55555556)
            setPadding(18, 8, 18, 8)
        }
        val t = TextView(ctx).apply {
            text = "AutoPilot: старт…"
            setTextColor(Color.WHITE)
            textSize = 11f
            maxWidth = 520
            setOnClickListener { onPause() }
        }
        val pa = TextView(ctx).apply {
            text = " II "
            setTextColor(-0xFFAA01)
            textSize = 14f
            setPadding(14, 4, 14, 4)
            setOnClickListener { onPause() }
        }
        val st = TextView(ctx).apply {
            text = " X "
            setTextColor(-0xADADAE)
            textSize = 14f
            setPadding(14, 4, 14, 4)
            setOnClickListener { onStop() }
        }
        row.addView(pa)
        row.addView(t)
        row.addView(st)
        val lp = base().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 12
            y = 40
        }
        wm.addView(row, lp)
        panel = row
        label = t
    }

    fun set(text: String) {
        val l = label
        if (l != null) l.text = "AutoPilot: $text"
    }

    fun hidePanel() {
        val p = panel
        if (p != null) try { wm.removeView(p) } catch (e: Throwable) {}
        panel = null
        label = null
    }

    fun showRec(onTap: (Mac.T) -> Unit) {
        if (rec != null) return
        var last = SystemClock.elapsedRealtime()
        var down = 0L
        var injectUntil = 0L
        val v = object : View(ctx) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.deviceId == -1) return true
                if (SystemClock.elapsedRealtime() < injectUntil) return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> down = SystemClock.elapsedRealtime()
                    MotionEvent.ACTION_UP -> {
                        val held = SystemClock.elapsedRealtime() - down
                        val now = SystemClock.elapsedRealtime()
                        val d = (now - last).toInt()
                        last = now
                        val isLong = held > 350
                        onTap(Mac.T(e.x.toInt(), e.y.toInt(), d, isLong))
                        injectUntil = SystemClock.elapsedRealtime() + 320
                        val ts = TouchService.inst
                        if (ts != null) {
                            if (isLong) ts.tap(e.x, e.y, held) else ts.tap(e.x, e.y)
                        }
                    }
                }
                return true
            }
        }
        wm.addView(v, base())
        rec = v
    }

    fun hideRec() {
        val r = rec
        if (r != null) try { wm.removeView(r) } catch (e: Throwable) {}
        rec = null
    }

    fun hideAll() {
        hideRec()
        hidePanel()
        hideDim()
    }
}

// ==================== Главная служба ====================

class AutoPilotService : Service() {

    companion object {
        @Volatile var inst: AutoPilotService? = null
        @Volatile var stepsLive = 0
        @Volatile var levelsLive = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Brain.A>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    private var thinker: Job? = null
    private var actor: Job? = null
    private var macroJob: Job? = null

    private var cap: Capture? = null
    private var ovl: Ovl? = null
    private var wake: PowerManager.WakeLock? = null

    private var pkg = ""
    private var mode = C.MODE_AI
    private var goal = ""
    private var maxLevels = 0
    private var maxMinutes = 0
    private var maxSteps = 0

    private var imgW = 1
    private var imgH = 1
    private var scrW = 1
    private var scrH = 1

    // рабочая зона: для сплит-скрина
    private var zoneY0 = 0
    private var viewH = 1

    private var startedAt = 0L
    private var levels = 0
    private var steps = 0
    private var errors = 0
    private var lastFrameAt = 0L
    private var black = 0
    private var stopped = false
    private var awayNotified = false

    private var lastTap: Pair<Int, Int>? = null
    private var zoomRect: IntArray? = null
    private val hist = ArrayList<String>()
    private val lvlTaps = ArrayList<Mac.T>()
    private var lastLvlTap = 0L

    @Volatile private var paused = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == C.STOP) {
            stopPilot("остановлено")
            return START_NOT_STICKY
        }

        val code = if (intent != null) intent.getIntExtra("code", 0) else 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else
            intent?.getParcelableExtra("data")

        pkg = if (intent != null) intent.getStringExtra("pkg").orEmpty() else ""
        mode = if (intent != null) intent.getIntExtra("mode", C.MODE_AI) else C.MODE_AI
        goal = if (intent != null) intent.getStringExtra("goal").orEmpty() else ""
        maxLevels = if (intent != null) intent.getIntExtra("maxLevels", 0) else 0
        maxMinutes = if (intent != null) intent.getIntExtra("maxMinutes", 0) else 0
        maxSteps = if (intent != null) intent.getIntExtra("maxSteps", 0) else 0
        val dim = if (intent != null) intent.getBooleanExtra("dim", true) else true
        val keep = if (intent != null) intent.getBooleanExtra("keepOn", true) else true
        val rawInterval = if (intent != null) intent.getIntExtra("interval", 900) else 900
        val cadence = rawInterval.toLong().coerceIn(300L, 5000L)
        val zone = if (intent != null) intent.getIntExtra("zone", C.ZONE_ALL) else C.ZONE_ALL

        if (data == null || pkg.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) hardStop()

        inst = this
        foreground()

        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ap:cpu")
        wake?.setReferenceCounted(false)
        wake?.acquire()

        val wmgr = getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        try { wmgr.defaultDisplay.getRealMetrics(dm) } catch (e: Throwable) {}
        if (dm.widthPixels > 0) {
            scrW = dm.widthPixels
            scrH = dm.heightPixels
        }
        imgW = (scrW / 2).coerceAtLeast(360)
        imgH = (scrH / 2).coerceAtLeast(360)

        zoneY0 = when (zone) {
            C.ZONE_BOTTOM -> imgH / 2
            else -> 0
        }
        viewH = if (zone == C.ZONE_ALL) imgH else (imgH / 2).coerceAtLeast(200)

        ovl = Ovl(this)
        ovl?.showDim(if (dim) 0.03f else 1f, keep)
        ovl?.showPanel({ togglePause() }, { stopPilot("стоп вручную") })

        val dpi = (resources.displayMetrics.densityDpi / 2).coerceAtLeast(120)
        cap = Capture(this, code, data, imgW, imgH, dpi)

        running.set(true)
        startedAt = SystemClock.elapsedRealtime()

        val sp = P.sp(this)
        val brain = Brain(
            sp.getString(P.BASE, P.DEF_BASE) ?: P.DEF_BASE,
            sp.getString(P.API, "") ?: "",
            sp.getString(P.MODEL, P.DEF_MODEL) ?: P.DEF_MODEL
        )

        scope.launch {
            delay(1500)
            launchGame()
            delay(if (mode == C.MODE_RECORD) 400 else 2200)
            when (mode) {
                C.MODE_RECORD -> {
                    ovl?.showRec { t ->
                        lvlTaps.add(t)
                        set("записано: ${lvlTaps.size}")
                    }
                    set("тапай по экрану")
                }
                C.MODE_AI -> ai(brain, cadence)
                C.MODE_MACRO -> macro(false)
                C.MODE_HYBRID -> {
                    macro(false)
                    ai(brain, cadence * 3)
                }
                C.MODE_SPEED -> {
                    macro(true)
                    ai(brain, cadence * 4)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Игра сейчас на экране? Если нет — бот ждёт, чтобы не мешать тебе. */
    private fun gameVisible(): Boolean {
        val f = TouchService.foregroundPkg
        if (f.isEmpty()) return true
        if (f == pkg) return true
        if (f == packageName) return true
        return false
    }

    private suspend fun waitForGame() {
        var first = true
        while (scope.isActive && running.get() && !gameVisible()) {
            if (first) {
                set("игра не на экране — жду")
                first = false
            }
            delay(250)
        }
    }

    private fun ai(brain: Brain, cadence: Long) {
        actor = scope.launch { actorLoop() }
        thinker = scope.launch { thinkLoop(brain, cadence) }
    }

    private fun actorLoopJob() = scope.launch { actorLoop() }

    private suspend fun actorLoop() {
        val sx = scrW.toFloat() / imgW
        val sy = scrH.toFloat() / imgH
        while (scope.isActive) {
            val a = queue.receive()
            var waited = 0L
            while (scope.isActive && !gameVisible() && waited < 3000L) {
                delay(150)
                waited += 150
            }
            while (paused && running.get()) delay(80)
            if (!running.get()) return
            val t = TouchService.inst
            when (a.t) {
                "tap" -> {
                    if (t != null) t.tap(a.x * sx, (a.y + zoneY0) * sy)
                    note(a.x, a.y)
                }
                "double" -> {
                    if (t != null) t.doubleTap(a.x * sx, (a.y + zoneY0) * sy)
                    note(a.x, a.y)
                }
                "long" -> {
                    if (t != null) t.tap(a.x * sx, (a.y + zoneY0) * sy, a.ms.toLong().coerceIn(200L, 4000L))
                    note(a.x, a.y)
                }
                "swipe" -> {
                    if (t != null) {
                        t.swipe(
                            a.x * sx, (a.y + zoneY0) * sy,
                            a.x2 * sx, (a.y2 + zoneY0) * sy,
                            a.ms.toLong().coerceIn(80L, 2000L)
                        )
                    }
                    note(a.x, a.y)
                }
                "drag" -> {
                    if (t != null) {
                        t.drag(a.x * sx, (a.y + zoneY0) * sy, a.x2 * sx, (a.y2 + zoneY0) * sy)
                    }
                    note(a.x, a.y)
                }
                "wait" -> delay(a.ms.toLong().coerceIn(100L, 5000L))
                "back" -> if (t != null) t.back()
                "restart" -> {
                    launchGame()
                    delay(3200)
                }
            }
            pending.decrementAndGet()
            delay(60)
        }
    }

    private fun note(x: Int, y: Int) {
        lastTap = x to y
        val now = SystemClock.elapsedRealtime()
        val d = if (lastLvlTap == 0L) 400 else (now - lastLvlTap).toInt()
        lastLvlTap = now
        if (lvlTaps.size < 200) lvlTaps.add(Mac.T(x, y, d.coerceIn(40, 4000), false))
    }

    /** Вырезает рабочую зону, если включён сплит-режим. */
    private fun zone(f: Bitmap): Bitmap {
        if (viewH >= f.height) return f
        return try {
            Bitmap.createBitmap(f, 0, zoneY0, f.width, viewH)
        } catch (e: Throwable) {
            f
        }
    }

    private suspend fun thinkLoop(brain: Brain, cadence: Long) {
        var lastSig = 0L
        var idle = 0
        var noOp = 0

        while (scope.isActive && running.get()) {
            if (paused) {
                delay(150)
                continue
            }
            if (maxSteps > 0 && steps >= maxSteps) {
                stopPilot("лимит шагов")
                return
            }
            if (maxMinutes > 0 && (SystemClock.elapsedRealtime() - startedAt) / 60000L >= maxMinutes.toLong()) {
                stopPilot("лимит времени")
                return
            }

            if (!gameVisible()) {
                if (!awayNotified) {
                    set("игра не на экране — жду")
                    awayNotified = true
                }
                delay(300)
                continue
            }
            awayNotified = false

            val full = cap?.frame()
            if (full == null) {
                if (SystemClock.elapsedRealtime() - lastFrameAt > 7000) cap?.recreate()
                delay(250)
                continue
            }
            lastFrameAt = SystemClock.elapsedRealtime()

            val f = zone(full)

            val cur = signature(f)
            if (cur == 0L) black++ else black = 0
            if (black > 45) {
                black = 0
                launchGame()
                delay(3000)
                continue
            }

            val changed = lastSig == 0L || ham(cur, lastSig) > 4
            if (!changed && noOp == 0 && idle < 4) {
                idle++
                delay(140)
                continue
            }
            idle = 0

            var drawn = Gr.draw(f)
            val lt = lastTap
            if (lt != null && noOp > 0) drawn = Gr.cross(drawn, lt.first, lt.second)

            val imgs = ArrayList<ByteArray>(2)
            imgs.add(drawn.toJpg(70))
            zoomRect = null
            if (noOp > 0 && lt != null) {
                val z = zoom(f, lt)
                if (z != null) imgs.add(z)
            }

            val before = cur
            lastSig = cur

            var res: Brain.S? = null
            try {
                errors = 0
                res = brain.ask(
                    system(),
                    userPrompt(noOp),
                    imgs,
                    { th -> set(th.take(70)) },
                    { a ->
                        pending.incrementAndGet()
                        queue.trySend(a)
                    }
                )
            } catch (e: Exception) {
                errors++
                set("ошибка ИИ ($errors)")
                if (errors >= 5 && Mac.load(this, pkg).isNotEmpty()) {
                    set("перехожу на макрос")
                    macro(false)
                    return
                }
                delay(1000)
            }

            val r = res
            if (r == null) continue

            steps++
            stepsLive = steps
            if (r.think.isNotBlank()) {
                hist.add("шаг $steps: ${r.think.take(80)}")
                while (hist.size > 5) hist.removeAt(0)
            }
            if (r.learn.isNotEmpty()) Mem.add(this, pkg, r.learn)
            if (r.lvl) {
                levels++
                levelsLive = levels
                saveLvl()
                set("уровней: $levels")
            }

            val dl = SystemClock.elapsedRealtime() + 12000
            while (pending.get() > 0 && SystemClock.elapsedRealtime() < dl) delay(40)
            delay(220)

            val after = cap?.frame()?.let { signature(zone(it)) } ?: 0L
            var hadTap = false
            for (a in r.acts) {
                if (a.t == "tap" || a.t == "double" || a.t == "long" || a.t == "swipe" || a.t == "drag") {
                    hadTap = true
                }
            }
            noOp = if (hadTap && ham(before, after) <= 3) (noOp + 1).coerceAtMost(6) else 0
            if (noOp >= 4) {
                set("застрял — перезапуск")
                launchGame()
                delay(2500)
                noOp = 0
            }

            if (r.done) {
                stopPilot(if (r.reason.isBlank()) "цель достигнута" else r.reason)
                return
            }
            if (maxLevels > 0 && levels >= maxLevels) {
                stopPilot("пройдено уровней: $levels")
                return
            }

            val spent = SystemClock.elapsedRealtime() - lastFrameAt
            if (spent < cadence) delay(cadence - spent)
        }
    }

    private fun zoom(f: Bitmap, p: Pair<Int, Int>): ByteArray? {
        return try {
            val rw = (f.width * 0.45f).toInt().coerceAtLeast(200)
            val rh = (f.height * 0.45f).toInt().coerceAtLeast(200)
            val x = (p.first - rw / 2).coerceIn(0, (f.width - rw).coerceAtLeast(1))
            val y = (p.second - rh / 2).coerceIn(0, (f.height - rh).coerceAtLeast(1))
            zoomRect = intArrayOf(x, y, rw, rh)
            val c = Bitmap.createBitmap(f, x, y, rw, rh)
            val up = Bitmap.createScaledBitmap(
                c, (rw * 2).coerceAtMost(1100), (rh * 2).coerceAtMost(1100), true
            )
            up.toJpg(82)
        } catch (e: Throwable) {
            null
        }
    }

    private fun saveLvl() {
        if (lvlTaps.size < 3) return
        Mac.save(this, pkg, ArrayList(lvlTaps))
        lvlTaps.clear()
        lastLvlTap = 0L
    }

    private fun macro(learned: Boolean) {
        macroJob?.cancel()
        macroJob = scope.launch {
            val m = Mac.load(this@AutoPilotService, pkg)
            if (m.isEmpty()) {
                set("макроса нет")
                return@launch
            }
            set(if (learned) "спидран" else "повтор макроса")
            while (isActive && running.get()) {
                for (t in m) {
                    while (paused && running.get()) delay(80)
                    while (isActive && running.get() && !gameVisible()) delay(250)
                    if (!running.get()) return@launch
                    val ts = TouchService.inst ?: break
                    if (t.l) ts.tap(t.x.toFloat(), t.y.toFloat(), 500L)
                    else ts.tap(t.x.toFloat(), t.y.toFloat())
                    delay(t.d.toLong().coerceIn(30L, 8000L))
                }
                delay(250)
            }
        }
    }

    private fun system(): String {
        val sb = StringBuilder()
        sb.append("Ты — лучший игрок в мире и играешь в мобильную игру на Android, управляя только касаниями.\n")
        sb.append("Кадр игры приходит картинкой ${imgW}x${viewH}. Игра: $pkg\n\n")
        sb.append("На картинке нарисована БИРЮЗОВАЯ СЕТКА с числами по краям — это пиксельные координаты. Сверяйся с ней.\n")
        sb.append("Красный крестик — куда ты нажимал в прошлый раз. Промахнулся — исправься.\n")
        sb.append("Панель с надписью AutoPilot сверху слева — интерфейс бота, не игра.\n")
        if (viewH < imgH) {
            sb.append("Это ВЕРХНЯЯ или НИЖНЯЯ ПОЛОВИНА экрана (сплит-скрин) — координаты считай от верхнего края этой картинки.\n")
        }
        val zr = zoomRect
        if (zr != null) {
            sb.append("\nКартинка 2 — увеличенный фрагмент области x=${zr[0]}..${zr[0] + zr[2]}, y=${zr[1]}..${zr[1] + zr[3]} в координатах картинки 1. Координаты указывай в системе картинки 1.")
        }
        val n = Mem.notes(this, pkg)
        if (n.isNotEmpty()) {
            sb.append("\n\nЧто ты знаешь об этой игре:\n")
            for (note in n) sb.append("  • ").append(note).append('\n')
        }
        sb.append("\n\nОТВЕЧАЙ СТРОГО JSON, порядок ключей: think, actions, levelDone, done, reason, learn\n")
        sb.append("{\"think\":\"что видишь и делаешь\",\"actions\":[{...}],\"levelDone\":false,\"done\":false,\"reason\":\"\",\"learn\":[]}\n\n")
        sb.append("Действия: tap {x,y} | double {x,y} | long {x,y,ms} | swipe {x,y,x2,y2,ms} | drag {x,y,x2,y2} | wait {ms} | back | restart\n\n")
        sb.append("ПРАВИЛА:\n")
        sb.append("1. Координаты в пикселях картинки 1, (0,0) слева сверху. Сверяйся с сеткой.\n")
        sb.append("2. Обычно 1–3 действия, до 6 только для связки. Действия выполняются сразу по мере выхода JSON.\n")
        sb.append("3. Тап не изменил экран — промахнулся. Смотри на крестик и картинку 2 (зум), тапни точнее.\n")
        sb.append("4. Загрузка/анимация → wait 1200.\n")
        sb.append("5. Уровень пройден → levelDone true и тапни Next/Continue.\n")
        sb.append("6. Реклама → закрой крестиком или «Пропустить». Реклама не конец уровня.\n")
        sb.append("7. Проигрыш → тапни Retry/Заново.\n")
        sb.append("8. Цель выполнена → done true и в reason что сделано.\n")
        sb.append("9. Новую закономерность пиши в learn. Не дублируй.\n")
        sb.append("10. Пустой actions не возвращай: не уверен — wait.\n")
        sb.append("11. Приоритеты как у профи: выживание, прогресс, бонусы.\n")
        return sb.toString()
    }

    private fun userPrompt(noOp: Int): String {
        val sb = StringBuilder()
        if (goal.isNotBlank()) sb.append("ЦЕЛЬ: $goal\n")
        sb.append("Шаг $steps, уровней пройдено: $levels\n")
        if (noOp > 0) sb.append("ВНИМАНИЕ: последние $noOp тапов не изменили экран.\n")
        if (hist.isNotEmpty()) {
            sb.append("Прошлые шаги:\n")
            for (h in hist) sb.append("  ").append(h).append('\n')
        }
        sb.append("Что делаешь?")
        return sb.toString()
    }

    private fun signature(b: Bitmap): Long {
        val v = IntArray(64)
        val sx = (b.width / 8).coerceAtLeast(1)
        val sy = (b.height / 8).coerceAtLeast(1)
        var i = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                v[i] = b.getPixel(
                    (x * sx + sx / 2).coerceAtMost(b.width - 1),
                    (y * sy + sy / 2).coerceAtMost(b.height - 1)
                ) and 0xFF
                i++
            }
        }
        val avg = v.sum() / 64
        var h = 0L
        for (j in 0 until 64) {
            if (v[j] > avg) h = h or (1L shl j)
        }
        return h
    }

    private fun ham(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(i)
    }

    fun togglePause() {
        paused = !paused
        set(if (paused) "ПАУЗА (громкость-вниз)" else "продолжаю")
        try {
            val v = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(50L, 128))
            } else {
                v.vibrate(50L)
            }
        } catch (e: Throwable) {}
    }

    private fun set(t: String) {
        if (t.isNotBlank()) ovl?.set(t)
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AutoPilotService::class.java).setAction(C.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "ap")
            .setContentTitle("Venice AutoPilot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(0, "Стоп", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ap", "AutoPilot", NotificationManager.IMPORTANCE_LOW)
        )
        val n = notif("готовлюсь…")
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(42, n, type)
        } else {
            startForeground(42, n)
        }
    }

    private fun stopPilot(reason: String) {
        if (stopped) return
        stopped = true
        running.set(false)
        if (mode == C.MODE_RECORD && lvlTaps.isNotEmpty()) {
            Mac.save(this, pkg, ArrayList(lvlTaps))
        }
        try {
            getSystemService(NotificationManager::class.java).notify(42, notif("Готово: $reason"))
        } catch (e: Throwable) {}
        try {
            val v = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(200L, 128))
            } else {
                v.vibrate(200L)
            }
        } catch (e: Throwable) {}
        hardStop()
    }

    private fun hardStop() {
        running.set(false)
        thinker?.cancel()
        actor?.cancel()
        macroJob?.cancel()
        cap?.release()
        cap = null
        ovl?.hideAll()
        ovl = null
        try { wake?.release() } catch (e: Throwable) {}
        wake = null
        inst = null
        try { stopForeground(true) } catch (e: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        thinker?.cancel()
        actor?.cancel()
        macroJob?.cancel()
        cap?.release()
        ovl?.hideAll()
        try { wake?.release() } catch (e: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }
}

// ==================== Экран приложения ====================

data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

class MainActivity : Activity() {

    private val sp by lazy { P.sp(this) }
    private var sel: String? = null
    private var pend = C.MODE_AI
    private val apps = ArrayList<AppEntry>()

    private lateinit var list: ListView
    private lateinit var count: TextView
    private lateinit var selLabel: TextView
    private lateinit var status: TextView
    private lateinit var goalField: EditText
    private lateinit var api: EditText
    private lateinit var base: EditText
    private lateinit var model: EditText
    private lateinit var ivl: EditText
    private lateinit var maxL: EditText
    private lateinit var maxM: EditText
    private lateinit var maxS: EditText
    private lateinit var onlyGames: CheckBox
    private lateinit var dimBox: CheckBox
    private lateinit var keepBox: CheckBox
    private lateinit var modes: RadioGroup
    private lateinit var zones: RadioGroup

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(20, 20, 20, 20)

        col.addView(txt("Venice AutoPilot", 22f, true))
        col.addView(txt("1) разрешения  2) выбери игру  3) СТАРТ", 12f, false))
        col.addView(txt("Бот сам встаёт на паузу, когда ты выходишь из игры.", 11f, false))

        val r1 = LinearLayout(this)
        val bAcc = Button(this)
        bAcc.text = "Спец. возможности"
        val bOvl = Button(this)
        bOvl.text = "Поверх окон"
        r1.addView(bAcc, wlp(1f))
        r1.addView(bOvl, wlp(1f))
        col.addView(r1)

        onlyGames = CheckBox(this)
        onlyGames.text = "только игры"
        onlyGames.isChecked = true
        count = txt("", 12f, false)
        val r2 = LinearLayout(this)
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.addView(onlyGames)
        r2.addView(count)
        col.addView(r2)

        list = ListView(this)
        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = apps[pos]
            sel = e.pkg
            selLabel.text = "Выбрано: ${e.label}"
            goalField.setText(Mem.goal(this, e.pkg))
            sp.edit().putString(P.LAST, e.pkg).apply()
            val a = list.adapter
            if (a is Adapter) a.notifyDataSetChanged()
        }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700))

        selLabel = txt("Выбрано: —", 14f, true)
        col.addView(selLabel)

        api = EditText(this)
        api.hint = "Venice API key"
        base = EditText(this)
        base.hint = "Base URL"
        model = EditText(this)
        model.hint = "vision-модель"
        goalField = EditText(this)
        goalField.hint = "Цель: пройти 10 уровней, фармить золото…"
        col.addView(api)
        col.addView(base)
        col.addView(model)
        col.addView(goalField)

        modes = RadioGroup(this)
        val mAi = RadioButton(this)
        mAi.text = "Режим ИИ"
        mAi.id = 1
        mAi.isChecked = true
        val mMac = RadioButton(this)
        mMac.text = "Режим макроса"
        mMac.id = 2
        val mHyb = RadioButton(this)
        mHyb.text = "Гибрид: макрос + ИИ"
        mHyb.id = 3
        val mSpd = RadioButton(this)
        mSpd.text = "Спидран: повтор выученного прохода"
        mSpd.id = 4
        modes.addView(mAi)
        modes.addView(mMac)
        modes.addView(mHyb)
        modes.addView(mSpd)
        col.addView(modes)

        zones = RadioGroup(this)
        val zAll = RadioButton(this)
        zAll.text = "зона: весь экран"
        zAll.id = 11
        zAll.isChecked = true
        val zTop = RadioButton(this)
        zTop.text = "зона: верхняя половина (сплит)"
        zTop.id = 12
        val zBot = RadioButton(this)
        zBot.text = "зона: нижняя половина (сплит)"
        zBot.id = 13
        zones.addView(zAll)
        zones.addView(zTop)
        zones.addView(zBot)
        col.addView(zones)

        val r3 = LinearLayout(this)
        r3.gravity = Gravity.CENTER_VERTICAL
        r3.addView(txt("интервал, мс:", 14f, false))
        ivl = EditText(this)
        ivl.setText("900")
        ivl.width = 260
        r3.addView(ivl)
        dimBox = CheckBox(this)
        dimBox.text = "тусклый экран"
        dimBox.isChecked = true
        keepBox = CheckBox(this)
        keepBox.text = "не гасить"
        keepBox.isChecked = true
        r3.addView(dimBox)
        r3.addView(keepBox)
        col.addView(r3)

        val r4 = LinearLayout(this)
        r4.gravity = Gravity.CENTER_VERTICAL
        r4.addView(txt("стоп после уровней:", 14f, false))
        maxL = EditText(this)
        maxL.setText("0")
        maxL.width = 170
        r4.addView(maxL)
        r4.addView(txt("минут:", 14f, false))
        maxM = EditText(this)
        maxM.setText("0")
        maxM.width = 170
        r4.addView(maxM)
        r4.addView(txt("шагов:", 14f, false))
        maxS = EditText(this)
        maxS.setText("0")
        maxS.width = 170
        r4.addView(maxS)
        col.addView(r4)

        status = txt("статус: не запущен", 13f, false)
        col.addView(status)

        val r5 = LinearLayout(this)
        val bTest = Button(this)
        bTest.text = "Тест API"
        val bRec = Button(this)
        bRec.text = "Запись"
        val bStart = Button(this)
        bStart.text = "СТАРТ"
        val bStop = Button(this)
        bStop.text = "СТОП"
        r5.addView(bTest, wlp(1f))
        r5.addView(bRec, wlp(1f))
        r5.addView(bStart, wlp(1f))
        r5.addView(bStop, wlp(1f))
        col.addView(r5)

        val bMem = Button(this)
        bMem.text = "Сбросить память игры"
        col.addView(bMem)

        setContentView(ScrollView(this).apply { addView(col) })

        api.setText(sp.getString(P.API, ""))
        base.setText(sp.getString(P.BASE, P.DEF_BASE))
        model.setText(sp.getString(P.MODEL, P.DEF_MODEL))
        goalField.setText(sp.getString(P.GOAL, ""))
        ivl.setText(sp.getInt(P.IVL, 900).toString())
        dimBox.isChecked = sp.getBoolean(P.DIM, true)
        keepBox.isChecked = sp.getBoolean(P.KEEP, true)
        when (sp.getInt(P.ZONE, C.ZONE_ALL)) {
            C.ZONE_TOP -> zones.check(12)
            C.ZONE_BOTTOM -> zones.check(13)
            else -> zones.check(11)
        }

        bAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Найди «Venice AutoPilot» и включи")
        }
        bOvl.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                toast("Уже разрешено")
            }
        }
        bTest.setOnClickListener { test() }
        bRec.setOnClickListener { flow(C.MODE_RECORD) }
        bStart.setOnClickListener { flow(currentMode()) }
        bStop.setOnClickListener {
            startService(Intent(this, AutoPilotService::class.java).setAction(C.STOP))
        }
        bMem.setOnClickListener {
            val p = sel
            if (p == null) {
                toast("Выбери игру")
            } else {
                Mem.clear(this, p)
                goalField.setText("")
                toast("Память сброшена")
            }
        }
        onlyGames.setOnCheckedChangeListener { _, _ -> load() }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }

        val h = Handler(Looper.getMainLooper())
        h.postDelayed(object : Runnable {
            override fun run() {
                status.text = if (AutoPilotService.inst == null) {
                    "статус: не запущен"
                } else {
                    "статус: работает, шагов ${AutoPilotService.stepsLive}, уровней ${AutoPilotService.levelsLive}"
                }
                h.postDelayed(this, 700)
            }
        }, 700)

        load()
    }

    private fun txt(t: String, sz: Float, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sz
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun wlp(w: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    private fun currentMode(): Int = when (modes.checkedRadioButtonId) {
        2 -> C.MODE_MACRO
        3 -> C.MODE_HYBRID
        4 -> C.MODE_SPEED
        else -> C.MODE_AI
    }

    private fun currentZone(): Int = when (zones.checkedRadioButtonId) {
        12 -> C.ZONE_TOP
        13 -> C.ZONE_BOTTOM
        else -> C.ZONE_ALL
    }

    private fun load() {
        val only = onlyGames.isChecked
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
                val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (ai.packageName == packageName) return@mapNotNull null
                val game = Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME
                if (only && !game) return@mapNotNull null
                AppEntry(ai.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm))
            }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps.clear()
                apps.addAll(found)
                val a = list.adapter
                if (a is Adapter) a.notifyDataSetChanged()
                count.text = "найдено: ${found.size}"
                val last = sp.getString(P.LAST, null)
                if (last != null) {
                    val f = found.firstOrNull { it.pkg == last }
                    if (f != null) {
                        sel = f.pkg
                        selLabel.text = "Выбрано: ${f.label}"
                        goalField.setText(Mem.goal(this, f.pkg))
                    }
                }
            }
        }.start()
    }

    private fun flow(m: Int) {
        val p = sel
        if (p == null) {
            toast("Сначала выбери игру")
            return
        }
        if (!TouchService.ok) {
            toast("Включи спец. возможности")
            return
        }
        if (m != C.MODE_RECORD && m != C.MODE_MACRO && api.text.toString().trim().isEmpty()) {
            toast("Вставь API-ключ")
            return
        }
        if (m == C.MODE_MACRO && Mac.load(this, p).isEmpty()) {
            toast("Макроса нет — сначала запиши")
            return
        }
        save()
        pend = m
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1001)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1001 && res == RESULT_OK && data != null) {
            val g = goalField.text.toString()
            val p = sel
            if (p != null) Mem.saveGoal(this, p, g)
            val i = Intent(this, AutoPilotService::class.java)
            i.action = C.START
            i.putExtra("code", res)
            i.putExtra("data", data)
            i.putExtra("pkg", p)
            i.putExtra("mode", pend)
            i.putExtra("interval", ivl.text.toString().toIntOrNull() ?: 900)
            i.putExtra("goal", g)
            i.putExtra("maxLevels", maxL.text.toString().toIntOrNull() ?: 0)
            i.putExtra("maxMinutes", maxM.text.toString().toIntOrNull() ?: 0)
            i.putExtra("maxSteps", maxS.text.toString().toIntOrNull() ?: 0)
            i.putExtra("dim", dimBox.isChecked)
            i.putExtra("keepOn", keepBox.isChecked)
            i.putExtra("zone", currentZone())
            startForegroundService(i)
            toast("Бот запущен")
        } else if (req == 1001) {
            toast("Захват экрана не разрешён")
        }
    }

    private fun save() {
        sp.edit()
            .putString(P.API, api.text.toString().trim())
            .putString(P.BASE, base.text.toString().trim())
            .putString(P.MODEL, model.text.toString().trim())
            .putString(P.GOAL, goalField.text.toString())
            .putInt(P.IVL, ivl.text.toString().toIntOrNull() ?: 900)
            .putBoolean(P.DIM, dimBox.isChecked)
            .putBoolean(P.KEEP, keepBox.isChecked)
            .putInt(P.ZONE, currentZone())
            .apply()
    }

    private fun test() {
        val k = api.text.toString().trim()
        if (k.isEmpty()) {
            toast("Нет ключа")
            return
        }
        toast("Проверяю…")
        Thread {
            val r = try {
                val b = Brain(base.text.toString().trim(), k, model.text.toString().trim())
                "OK: " + b.ping().take(60)
            } catch (e: Exception) {
                "Ошибка: " + (e.message ?: "?").take(120)
            }
            runOnUiThread { toast(r) }
        }.start()
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int): Any = apps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val e = apps[p]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(12, 12, 12, 12)
            if (e.pkg == sel) row.setBackgroundColor(0x3300AAFF)
            val ic = ImageView(this@MainActivity)
            ic.setImageDrawable(e.icon)
            row.addView(ic, LinearLayout.LayoutParams(96, 96))
            val c = LinearLayout(this@MainActivity)
            c.orientation = LinearLayout.VERTICAL
            c.setPadding(24, 0, 0, 0)
            val t1 = TextView(this@MainActivity)
            t1.text = e.label
            t1.textSize = 16f
            val t2 = TextView(this@MainActivity)
            t2.text = e.pkg
            t2.textSize = 11f
            c.addView(t1)
            c.addView(t2)
            row.addView(c)
            return row
        }
    }
}

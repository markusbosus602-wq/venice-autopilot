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
import android.text.Editable
import android.text.TextWatcher
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
import kotlin.math.roundToInt

// ==================== Настройки (всё хранится на диске) ====================

object P {
    const val F = "autopilot"
    const val API = "api"
    const val BASE = "base"
    const val MODEL = "model"
    const val MODE = "mode"
    const val AUTO = "auto"
    const val GOAL = "goal"
    const val RULES = "rules"
    const val ADS = "ads"
    const val IVL = "ivl"
    const val DIM = "dim"
    const val KEEP = "keep"
    const val LAST = "last"
    const val ZONE = "zone"
    const val TARGET = "target"
    const val METRIC = "metric"
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
    const val ADS_SKIP = 0
    const val ADS_WATCH = 1
    const val START = "ai.venice.autopilot.START"
    const val STOP = "ai.venice.autopilot.STOP"
    const val RULES_TEMPLATE =
        "Собираю только полезное, опасное не трогаю.\n" +
        "Апгрейды покупаю, как только хватает ресурсов.\n" +
        "Рекламу пропускаю сразу.\n" +
        "Играю уровень до победы, аптечки и бонусы забираю."
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
    fun rules(c: Context, p: String) = P.sp(c).getString("rules_$p", "") ?: ""
    fun saveRules(c: Context, p: String, r: String) {
        if (r.isBlank()) P.sp(c).edit().remove("rules_$p").apply()
        else P.sp(c).edit().putString("rules_$p", r).apply()
    }
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

// ==================== Тапы и жесты ====================

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

    fun hold(x: Float, y: Float, ms: Long) = tap(x, y, ms)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 250L) {
        send(stroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms))
    }

    fun dragTo(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        send(stroke(p, ms, 120L))
    }

    fun drag(x1: Float, y1: Float, x2: Float, y2: Float) = dragTo(x1, y1, x2, y2, 700L)

    fun pathMove(pts: List<Pair<Float, Float>>, ms: Long) {
        if (pts.size < 2) return
        val p = Path()
        p.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) p.lineTo(pts[i].first, pts[i].second)
        send(stroke(p, ms, 100L))
    }

    fun wiggle(x1: Float, y1: Float, x2: Float, y2: Float, times: Int, perPass: Long) {
        val p = Path()
        p.moveTo(x1, y1)
        var i = 0
        while (i < times) {
            p.lineTo(x2, y2)
            p.lineTo(x1, y1)
            i++
        }
        val total = (perPass * 2L * times).coerceIn(150L, 5000L)
        send(stroke(p, total, 80L))
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

    data class A(
        val t: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val ms: Int = 0,
        val x3: Float = 0f,
        val y3: Float = 0f,
        val n: Int = 0
    )

    data class S(
        val think: String = "",
        val acts: List<A> = emptyList(),
        val lvl: Boolean = false,
        val value: Int = -1,
        val done: Boolean = false,
        val reason: String = "",
        val learn: List<String> = emptyList(),
        val rules: List<String> = emptyList(),
        val playing: Boolean = false,
        val raw: String = ""
    )

    fun ask(sys: String, userText: String, imgs: List<ByteArray>, onThink: (String) -> Unit, onAct: (A) -> Unit): S {
        val root = JSONObject()
        root.put("model", model)
        root.put("temperature", 0.15)
        root.put("max_tokens", 1100)
        root.put("stream", true)
        if (base.contains("googleapis")) root.put("reasoning_effort", "low")

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
                var ct = ""
                var rc = ""
                try {
                    val delta = JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("delta")
                    if (delta != null) {
                        ct = delta.optString("content").orEmpty()
                        rc = delta.optString("reasoning_content").orEmpty()
                        if (rc.isEmpty()) rc = delta.optString("reasoning").orEmpty()
                    }
                } catch (e: Exception) {}
                val chunk = if (ct.isNotEmpty()) ct else rc
                if (chunk.isNotEmpty()) parser.feed(chunk)
            }
        }
        return parser.finish()
    }

    fun ping(): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("max_tokens", 30)
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
        c.readTimeout = 90000
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
            var j: JSONObject? = null
            var from = raw.lastIndexOf('{')
            var tries = 0
            val end = raw.lastIndexOf('}')
            while (from >= 0 && tries < 10 && j == null) {
                if (end > from) {
                    try {
                        val o = JSONObject(raw.substring(from, end + 1))
                        if (o.has("actions") || o.has("think") || o.has("levelDone")) j = o
                    } catch (e: Exception) {}
                }
                from = raw.lastIndexOf('{', from - 1)
                tries++
            }
            var lvl = false
            var done = false
            var reason = ""
            var think = ""
            var value = -1
            var learn = emptyList<String>()
            var rules = emptyList<String>()
            var playing = false
            val o = j
            if (o != null) {
                think = o.optString("think", "")
                lvl = o.optBoolean("levelDone", false)
                done = o.optBoolean("done", false)
                reason = o.optString("reason", "")
                value = o.optInt("value", -1)
                playing = o.optBoolean("playing", false)
                val arr = o.optJSONArray("learn")
                if (arr != null) learn = (0 until arr.length()).map { arr.optString(it) }
                val rr = o.optJSONArray("rules")
                if (rr != null) rules = (0 until rr.length()).map { rr.optString(it) }
            }
            return S(think, out, lvl, value, done, reason, learn, rules, playing, raw.takeLast(400))
        }

        private fun f(o: JSONObject, vararg keys: String): Float {
            for (k in keys) {
                if (o.has(k) && !o.isNull(k)) {
                    val d = o.optDouble(k, Double.NaN)
                    if (!d.isNaN()) return d.toFloat()
                }
            }
            return 0f
        }

        private fun i(o: JSONObject, vararg keys: String): Int {
            for (k in keys) {
                if (o.has(k) && !o.isNull(k)) {
                    val d = o.optDouble(k, Double.NaN)
                    if (!d.isNaN()) return Math.round(d).toInt()
                }
            }
            return 0
        }

        private fun norm(t: String, hasEnd: Boolean): String {
            val s = t.lowercase()
            return when {
                s.contains("wiggl") || s.contains("shak") || s.contains("туда") -> "wiggle"
                s.contains("path") || s.contains("пут") -> "path"
                s.contains("drag") || s.contains("move") || s.contains("slide") ||
                    s.contains("перетас") || s.contains("вед") -> if (hasEnd) "drag" else "hold"
                s.contains("swip") || s.contains("свайп") -> if (hasEnd) "swipe" else "tap"
                s.contains("hold") || s.contains("long") || s.contains("заж") ||
                    s.contains("удерж") -> "hold"
                s.contains("double") || s.contains("двой") -> "double"
                s.contains("wait") || s.contains("sleep") || s.contains("пауз") ||
                    s.contains("ждать") -> "wait"
                s.contains("back") || s.contains("назад") -> "back"
                s.contains("restart") || s.contains("перезапус") -> "restart"
                s.contains("tap") || s.contains("click") || s.contains("touch") ||
                    s.contains("press") || s.contains("наж") || s.contains("клик") -> "tap"
                else -> if (hasEnd) "drag" else "tap"
            }
        }

        fun parse(json: String): A? = try {
            val o = JSONObject(json)
            val x = f(o, "x", "x1", "startX", "startx", "fromX", "fromx")
            val y = f(o, "y", "y1", "startY", "starty", "fromY", "fromy")
            val x2 = f(o, "x2", "endX", "endx", "toX", "tox", "targetX", "targetx")
            val y2 = f(o, "y2", "endY", "endy", "toY", "toy", "targetY", "targety")
            val x3 = f(o, "x3", "midX", "midx")
            val y3 = f(o, "y3", "midY", "midy")
            val ms = i(o, "ms", "duration", "dur", "time")
            val n = i(o, "n", "times", "count", "repeat")
            val rawT = o.optString("type", "")
                .ifBlank { o.optString("action", "") }
                .ifBlank { o.optString("t", "tap") }
            val hasEnd = x2 != 0f || y2 != 0f
            val t = norm(rawT, hasEnd)
            var m = ms
            if (t == "wait" && m <= 0) m = 800
            A(t, x, y, x2, y2, m, x3, y3, n)
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
            maxWidth = 700
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
        @Volatile var lastReply = ""
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
    private var autostart = true
    private var maxLevels = 0
    private var maxMinutes = 0
    private var maxSteps = 0

    private var target = 0
    private var metric = ""
    private var adsMode = C.ADS_SKIP
    private var hitCount = 0
    private var lastValue = -1

    private var imgW = 1
    private var imgH = 1
    private var scrW = 1
    private var scrH = 1

    private var zoneY0 = 0
    private var viewH = 1

    private var startedAt = 0L
    private var levels = 0
    private var steps = 0
    private var errors = 0
    private var lastFrameAt = 0L
    private var lastActAt = 0L
    private var prevSig = 0L
    private var frozen = 0
    private var awaySince = 0L
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
        autostart = if (intent != null) intent.getBooleanExtra("auto", true) else true
        maxLevels = if (intent != null) intent.getIntExtra("maxLevels", 0) else 0
        maxMinutes = if (intent != null) intent.getIntExtra("maxMinutes", 0) else 0
        maxSteps = if (intent != null) intent.getIntExtra("maxSteps", 0) else 0
        target = if (intent != null) intent.getIntExtra("target", 0) else 0
        metric = if (intent != null) intent.getStringExtra("metric").orEmpty() else ""
        adsMode = if (intent != null) intent.getIntExtra("ads", C.ADS_SKIP) else C.ADS_SKIP
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

        zoneY0 = if (zone == C.ZONE_BOTTOM) imgH / 2 else 0
        viewH = if (zone == C.ZONE_ALL) imgH else (imgH / 2).coerceAtLeast(200)

        ovl = Ovl(this)
        ovl?.showDim(if (dim) 0.03f else 1f, keep)
        ovl?.showPanel({ togglePause() }, { stopPilot("стоп вручную") })

        val dpi = (resources.displayMetrics.densityDpi / 2).coerceAtLeast(120)
        cap = Capture(this, code, data, imgW, imgH, dpi)

        running.set(true)
        startedAt = SystemClock.elapsedRealtime()
        lastActAt = startedAt

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

    private fun gameVisible(): Boolean {
        val f = TouchService.foregroundPkg
        val vis = f.isEmpty() || f == pkg || f == packageName
        if (vis) {
            awaySince = 0L
            return true
        }
        if (awaySince == 0L) awaySince = SystemClock.elapsedRealtime()
        return SystemClock.elapsedRealtime() - awaySince > 5000
    }

    private fun ai(brain: Brain, cadence: Long) {
        actor = scope.launch { actorLoop() }
        thinker = scope.launch { thinkLoop(brain, cadence) }
    }

    private fun px(v: Float, size: Int): Float =
        if (v > 0f && v <= 1f) v * size else v

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
            val ax = px(a.x, imgW) * sx
            val ay = (px(a.y, imgH) + zoneY0) * sy
            val bx = px(a.x2, imgW) * sx
            val by = (px(a.y2, imgH) + zoneY0) * sy
            when (a.t) {
                "tap" -> {
                    if (t != null) {
                        if (a.ms > 60) t.hold(ax, ay, a.ms.toLong().coerceIn(80L, 4000L))
                        else t.tap(ax, ay)
                    }
                    note(a.x, a.y)
                }
                "double" -> {
                    if (t != null) t.doubleTap(ax, ay)
                    note(a.x, a.y)
                }
                "hold", "long" -> {
                    if (t != null) t.hold(ax, ay, a.ms.toLong().coerceIn(150L, 5000L))
                    note(a.x, a.y)
                }
                "swipe" -> {
                    if (t != null) t.swipe(ax, ay, bx, by, a.ms.toLong().coerceIn(60L, 1500L))
                    note(a.x, a.y)
                }
                "drag" -> {
                    if (t != null) t.dragTo(ax, ay, bx, by, a.ms.toLong().coerceIn(150L, 4000L))
                    note(a.x, a.y)
                }
                "path" -> {
                    if (t != null) {
                        val pts = ArrayList<Pair<Float, Float>>(3)
                        pts.add(ax to ay)
                        pts.add(bx to by)
                        if (a.x3 != 0f || a.y3 != 0f) {
                            pts.add(px(a.x3, imgW) * sx to (px(a.y3, imgH) + zoneY0) * sy)
                        }
                        t.pathMove(pts, a.ms.toLong().coerceIn(200L, 4000L))
                    }
                    note(a.x, a.y)
                }
                "wiggle" -> {
                    if (t != null) {
                        t.wiggle(
                            ax, ay, bx, by,
                            a.n.coerceIn(1, 6),
                            a.ms.toLong().coerceIn(120L, 1200L)
                        )
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
            lastActAt = SystemClock.elapsedRealtime()
            pending.decrementAndGet()
            delay(60)
        }
    }

    private fun note(x: Float, y: Float) {
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        lastTap = xi to yi
        val now = SystemClock.elapsedRealtime()
        val d = if (lastLvlTap == 0L) 400 else (now - lastLvlTap).toInt()
        lastLvlTap = now
        if (lvlTaps.size < 200) lvlTaps.add(Mac.T(xi, yi, d.coerceIn(40, 4000), false))
    }

    private fun tapImg(x: Int, y: Int) {
        val t = TouchService.inst ?: return
        val sx = scrW.toFloat() / imgW
        val sy = scrH.toFloat() / imgH
        t.tap(x * sx, y * sy)
        lastTap = x to y
        lastActAt = SystemClock.elapsedRealtime()
    }

    private fun zone(f: Bitmap): Bitmap {
        if (viewH >= f.height) return f
        return try {
            Bitmap.createBitmap(f, 0, zoneY0, f.width, viewH)
        } catch (e: Throwable) {
            f
        }
    }

    // ---------- этап входа: сам нахожу старт ----------

    private suspend fun entryLoop(brain: Brain, cadence: Long) {
        set("сам ищу кнопку старта…")
        val t0 = SystemClock.elapsedRealtime()
        var n = 0
        var idleSteps = 0
        while (scope.isActive && running.get() && n < 20 &&
            SystemClock.elapsedRealtime() - t0 < 50000L
        ) {
            n++
            if (paused) { delay(150); continue }
            if (!gameVisible()) { delay(300); continue }
            val full = cap?.frame()
            if (full == null) {
                if (SystemClock.elapsedRealtime() - lastFrameAt > 7000) cap?.recreate()
                delay(250)
                continue
            }
            lastFrameAt = SystemClock.elapsedRealtime()
            val f = zone(full)
            val drawn = Gr.draw(f)
            val acts = ArrayList<Brain.A>()
            var res: Brain.S? = null
            try {
                res = brain.ask(
                    entrySystem(),
                    "Вход в игру, попытка $n. Что нажать, чтобы начать играть?",
                    listOf(drawn.toJpg(70)),
                    { th -> set("вход: " + th.take(60)) },
                    { a ->
                        acts.add(a)
                        pending.incrementAndGet()
                        queue.trySend(a)
                    }
                )
            } catch (e: Exception) {
                val em = e.message ?: ""
                if (em.contains("429")) {
                    set("лимит запросов — пауза 10с")
                    delay(10000)
                } else {
                    set("ошибка ИИ на входе: " + em.take(40))
                }
                delay(1200)
                continue
            }

            lastReply = res.raw

            val dl = SystemClock.elapsedRealtime() + 12000
            while (pending.get() > 0 && SystemClock.elapsedRealtime() < dl) delay(40)

            if (res.playing) {
                set("игра началась")
                delay(400)
                return
            }

            if (acts.isEmpty()) {
                idleSteps++
                if (idleSteps >= 2) {
                    tapImg(imgW / 2, zoneY0 + (viewH * 0.72).toInt())
                    idleSteps = 0
                    set("вход: сам жму центр")
                }
            } else {
                idleSteps = 0
            }

            delay(cadence.coerceAtLeast(700L))
        }
        set("старт не найден — играю как есть")
        delay(300)
    }

    private suspend fun thinkLoop(brain: Brain, cadence: Long) {
        var lastSig = 0L
        var idle = 0
        var noOp = 0

        if (autostart && (mode == C.MODE_AI || mode == C.MODE_HYBRID)) {
            entryLoop(brain, cadence)
        }
        lastSig = 0L
        prevSig = 0L

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

            // пульс: нет действий дольше 6 секунд — сам тапаю
            if (SystemClock.elapsedRealtime() - lastActAt > 6000L) {
                tapImg(imgW / 2, zoneY0 + (viewH * 0.7).toInt())
                set("нет действий 6с — сам тапаю")
            }

            val full = cap?.frame()
            if (full == null) {
                if (SystemClock.elapsedRealtime() - lastFrameAt > 7000) cap?.recreate()
                delay(250)
                continue
            }
            lastFrameAt = SystemClock.elapsedRealtime()

            val f = zone(full)

            val cur = signature(f)
            if (cur == prevSig) frozen++ else frozen = 0
            prevSig = cur
            if (frozen > 90) {
                frozen = 0
                set("кадр застыл — перезапуск игры")
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
                    { th -> set("думает: " + th.take(55)) },
                    { a ->
                        pending.incrementAndGet()
                        queue.trySend(a)
                    }
                )
            } catch (e: Exception) {
                errors++
                val em = e.message ?: ""
                if (em.contains("429")) {
                    set("лимит запросов — пауза 10с")
                    delay(10000)
                } else {
                    set("ошибка ИИ: " + em.take(40))
                }
                if (errors >= 5 && Mac.load(this, pkg).isNotEmpty()) {
                    set("перехожу на макрос")
                    macro(false)
                    return
                }
                delay(1000)
            }

            val r = res
            if (r == null) continue
            lastReply = r.raw

            steps++
            stepsLive = steps
            if (r.think.isNotBlank()) {
                hist.add("шаг $steps: ${r.think.take(80)}")
                while (hist.size > 5) hist.removeAt(0)
            }
            if (r.learn.isNotEmpty()) Mem.add(this, pkg, r.learn)
            if (r.rules.isNotEmpty()) Mem.add(this, pkg, r.rules.map { "МЕХАНИКА: $it" })
            if (r.lvl) {
                levels++
                levelsLive = levels
                saveLvl()
                set("уровней: $levels")
            }

            if (target > 0 && r.value >= 0) {
                lastValue = r.value
                set("$metric: ${r.value} / $target")
                if (r.value >= target) {
                    hitCount++
                    if (hitCount >= 2) {
                        stopPilot("цель достигнута: $metric = ${r.value}")
                        return
                    }
                } else {
                    hitCount = 0
                }
            }

            val dl = SystemClock.elapsedRealtime() + 16000
            while (pending.get() > 0 && SystemClock.elapsedRealtime() < dl) delay(40)
            delay(200)

            val after = cap?.frame()?.let { signature(zone(it)) } ?: 0L
            var hadTap = false
            for (a in r.acts) {
                if (a.t == "tap" || a.t == "double" || a.t == "long" || a.t == "hold" ||
                    a.t == "swipe" || a.t == "drag" || a.t == "path" || a.t == "wiggle"
                ) {
                    hadTap = true
                }
            }
            noOp = if (hadTap && ham(before, after) <= 3) (noOp + 1).coerceAtMost(6) else 0

            if (r.acts.isEmpty()) {
                set("шаг $steps: ИИ молчит — сам тапаю")
                tapImg(imgW / 2, zoneY0 + (viewH * 0.6).toInt())
            } else if (!hadTap) {
                set("шаг $steps: ${r.acts[0].t} (${r.acts.size})")
            } else {
                set("шаг $steps: ${r.acts.size} действ. ${r.acts[0].t}")
            }

            if (noOp == 2) {
                tapImg(imgW / 2, zoneY0 + (viewH * 0.78).toInt())
                set("сам жму главную кнопку")
            }
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

    // ---------- промпты ----------

    private fun universal(): String {
        val sb = StringBuilder()
        sb.append("\n=== КАК ЧИТАТЬ ЭКРАН (работает всегда, правила игры это дополняют) ===\n")
        sb.append("• Всплывающее окно: фон затемнён, в центре панель. Закрывается крестиком в правом верхнем углу, кнопкой «Закрыть/Продолжить/ОК/Забрать» или тапом вне окна.\n")
        sb.append("• Главная кнопка запуска: крупная яркая кнопка в центре или снизу (зелёная, жёлтая, синяя), часто со словом Play/Играть/Старт/Начать/Продолжить/Уровень. Начинай с неё.\n")
        sb.append("• Экран со звёздами, «Level Complete», наградой → жми Next/Продолжить/стрелку вправо/зелёную кнопку.\n")
        sb.append("• Экран проигрыша → жми Retry/Заново/Продолжить.\n")
        sb.append("• Обучение и подсказки: жми туда, куда показывает рука или стрелка. Нарисованный палец — указание куда нажать или куда вести.\n")
        sb.append("• Ежедневная награда, подарок, сундук, колесо → забери (Claim/Забрать/Открыть/Крутить), это бесплатно.\n")
        sb.append("• Магазин и апгрейды: хватает ресурсов — покупай то, что помогает цели. Не хватает — выходи.\n")
        sb.append("• Совсем не понял экран → тап по центру, wait 900, посмотри, что изменилось.\n")
        sb.append("• Ты работаешь САМ. Не жди человека, не останавливайся, всегда что-то делай.\n")
        return sb.toString()
    }

    private fun control(): String {
        val sb = StringBuilder()
        sb.append("\n=== КАК УПРАВЛЯТЬ: ТАПЫ, ЗАЖИМ, ВОЖДЕНИЕ (очень важно) ===\n")
        sb.append("• Объект надо ВЕЗТИ/ВОДИТЬ/КАТАТЬ (ведро, корзина, тачка, платформа, персонаж, палец): хватай объект и веди — drag {x,y,x2,y2,ms}. Палец ставится в x,y и едет до x2,y2 за ms мс. Медленно = ms 1200, быстро = ms 250.\n")
        sb.append("• Водить ТУДА-СЮДА между двумя точками: wiggle {x,y,x2,y2,n,ms} — n раз туда-обратно одним жестом. Это для «дёргай ведро туда-сюда».\n")
        sb.append("• Вести по сложному пути в обход: path {x,y,x2,y2,x3,y3,ms} — палец идёт по трём точкам (можно объехать опасное).\n")
        sb.append("• Зажать и держать, не отпуская (зарядка, удержание кнопки, поднять объект): hold {x,y,ms}.\n")
        sb.append("• Джойстик: hold на джойстике и веди drag в нужную сторону. Меняй направление, не отпуская.\n")
        sb.append("• Резкий бросок/удар: swipe {x,y,x2,y2,ms} с маленьким ms (150).\n")
        sb.append("• Ведро/корзина с падающими предметами: держи палец НА ведре и веди его ПОД хороший предмет (drag или path). Опасное — пропускай или уводи ведро в сторону.\n")
        sb.append("• Персонаж-раннер: держи палец и води влево-вправо, чтобы уклоняться (wiggle или серия drag).\n")
        sb.append("• Если объект должен ДВИГАТЬСЯ — не тапай по нему несколько раз, а ВЕДИ его.\n")
        sb.append("• Поле type пиши строго одним из слов: tap, hold, drag, path, wiggle, swipe, wait, back, restart. Никаких click/move/press.\n")
        sb.append("• Координаты пиши целыми числами в пикселях картинки, например \"x\": 270, \"y\": 1180.\n")
        sb.append("• Определи тип управления игры и запиши это в rules один раз, например: «управление: вести ведро влево-вправо».\n")
        return sb.toString()
    }

    private fun entrySystem(): String {
        val sb = StringBuilder()
        sb.append("Ты — умный бот, который ведёт мобильную игру на Android касаниями.\n")
        sb.append("Сейчас этап ВХОДА В ИГРУ: экран меню, заставка, попап, обучение или загрузка.\n")
        sb.append("Кадр приходит картинкой ${imgW}x${viewH}, на ней бирюзовая сетка с координатами. Игра: $pkg\n")
        sb.append("Красный крестик — куда жал в прошлый раз.\n")
        val rules = Mem.rules(this, pkg)
        if (rules.isNotBlank()) {
            sb.append("\n=== ПРАВИЛА ИГРЫ (главный закон) ===\n").append(rules.trim()).append('\n')
        }
        sb.append(universal())
        sb.append(control())
        sb.append("\nЗАДАЧА ЭТАПА: добраться до игрового процесса.\n")
        sb.append("Найди и нажми кнопку запуска игры или уровня (Play, Играть, Старт, Начать, Продолжить, номер уровня). Закрой попапы и туториал. Если в обучении надо вести объект — веди его (drag).\n")
        sb.append("Если игра уже идёт (видно поле, персонажа, счёт) — поставь \"playing\": true и ничего не жми.\n")
        sb.append("Если идёт загрузка (крутится колёсико, полоса прогресса) — wait 1200 и \"playing\": false.\n")
        sb.append("\nОТВЕТ СТРОГО JSON: {\"think\":\"что вижу и что жму\",\"actions\":[{...}],\"playing\":false}\n")
        sb.append("Пример: {\"think\":\"вижу кнопку Play\",\"actions\":[{\"type\":\"tap\",\"x\":270,\"y\":1180}],\"playing\":false}\n")
        sb.append("Максимум 2 действия. Пустой actions не возвращай: не уверен — wait 800.\n")
        return sb.toString()
    }

    private fun system(): String {
        val sb = StringBuilder()
        sb.append("Ты — лучший игрок в мире и играешь в мобильную игру на Android, управляя только касаниями.\n")

        val rules = Mem.rules(this, pkg)
        if (rules.isNotBlank()) {
            sb.append("\n=== ПРАВИЛА ИГРЫ — ГЛАВНЫЙ ЗАКОН, приоритет выше всех пунктов ниже ===\n")
            sb.append(rules.trim()).append('\n')
            sb.append("=== конец правил ===\n")
        }

        sb.append("Кадр игры приходит картинкой ${imgW}x${viewH}. Игра: $pkg\n")
        sb.append("На картинке нарисована БИРЮЗОВАЯ СЕТКА с числами по краям — это пиксельные координаты. Сверяйся с ней.\n")
        sb.append("Красный крестик — куда ты нажимал в прошлый раз. Промахнулся — исправься.\n")
        sb.append("Панель с надписью AutoPilot сверху слева — интерфейс бота, не игра.\n")
        if (viewH < imgH) {
            sb.append("Это половина экрана (сплит-скрин) — координаты считай от верхнего края этой картинки.\n")
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

        sb.append(universal())
        sb.append(control())

        sb.append("\nОТВЕЧАЙ СТРОГО JSON, порядок ключей: think, actions, levelDone, value, done, reason, learn, rules\n")
        sb.append("{\"think\":\"что видишь и делаешь\",\"actions\":[{...}],\"levelDone\":false,\"value\":-1,\"done\":false,\"reason\":\"\",\"learn\":[],\"rules\":[]}\n")
        sb.append("Пример действия: {\"type\":\"tap\",\"x\":270,\"y\":1180} или {\"type\":\"drag\",\"x\":200,\"y\":1500,\"x2\":700,\"y2\":1500,\"ms\":400}\n\n")
        sb.append("Поле type строго из списка: tap, double, hold, swipe, drag, path, wiggle, wait, back, restart.\n")
        sb.append("Координаты — целые числа в пикселях картинки 1.\n\n")
        if (target > 0) {
            sb.append("=== ГЛАВНАЯ ЧИСЛОВАЯ ЦЕЛЬ ===\n")
            sb.append("Показатель, за которым надо следить: «$metric». Целевое значение: $target.\n")
            sb.append("В КАЖДОМ ответе заполняй поле \"value\" — текущее значение этого показателя, ТОЛЬКО ЦИФРЫ, без букв, пробелов и знаков (например 1240). Если показатель на экране не виден — \"value\": -1.\n")
            sb.append("Играй так, чтобы этот показатель рос: зарабатывай ресурсы и трать их на прокачку именно этого показателя.\n")
            sb.append("Когда показатель станет $target или больше — поставь \"done\": true и в \"reason\" напиши достигнутое значение.\n\n")
        }
        sb.append("ПРАВИЛА РАБОТЫ:\n")
        sb.append("1. Координаты в пикселях картинки 1, (0,0) слева сверху. Сверяйся с сеткой.\n")
        sb.append("2. Обычно 1–3 действия, до 6 только для связки. Действия выполняются сразу по мере выхода JSON.\n")
        sb.append("3. Тап не изменил экран — не повторяй тот же тап. Попробуй рядом, либо drag/hold, если объект надо вести или держать.\n")
        sb.append("4. Загрузка/анимация → wait 1200.\n")
        sb.append("5. Уровень пройден → levelDone true и тапни Next/Continue.\n")
        if (adsMode == C.ADS_WATCH) {
            sb.append("6. РЕКЛАМА (режим «смотреть за награду»): рекламу за награду досматривай и забирай награду; обычную рекламу между уровнями закрывай сразу. Реклама — не конец уровня.\n")
        } else {
            sb.append("6. РЕКЛАМА (режим «пропускать»): закрывай ЛЮБУЮ рекламу максимально быстро — крестик, «Пропустить», «Закрыть», тап по экрану. Если крестика нет 3–5 секунд, тапни ещё раз. Реклама — не конец уровня.\n")
        }
        sb.append("7. Проигрыш → тапни Retry/Заново.\n")
        sb.append("8. Цель выполнена → done true и в reason что сделано.\n")
        sb.append("9. Новую закономерность пиши в learn, а механику и тип управления — в rules. Не дублируй то, что уже знаешь.\n")
        sb.append("10. Пустой actions не возвращай: не уверен — wait.\n")
        sb.append("11. Если экран снова стал меню, магазином или выскочил попап — сначала разберись с ним, потом возвращайся к игре.\n")
        sb.append("12. Приоритеты как у профи: выживание, прогресс, бонусы. Прокачку не забывай — заходи в меню апгрейдов, когда есть ресурсы.\n")
        return sb.toString()
    }

    private fun userPrompt(noOp: Int): String {
        val sb = StringBuilder()
        if (goal.isNotBlank()) sb.append("ЦЕЛЬ: $goal\n")
        sb.append("Шаг $steps, уровней пройдено: $levels\n")
        if (target > 0) sb.append("Текущий $metric: $lastValue (цель $target)\n")
        if (noOp > 0) {
            sb.append("ВНИМАНИЕ: последние $noOp действий не изменили экран.\n")
            if (noOp == 1) sb.append("Ты попал мимо или действие не сработало. Если объект надо вести — используй drag, а не тап.\n")
            if (noOp >= 2) sb.append("Экран застыл: похоже, это меню или попап. Ищи кнопку запуска или закрытия, смотри блок «КАК ЧИТАТЬ ЭКРАН».\n")
        }
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
                v.vibrate(VibrationEffect.createOneShot(600L, 128))
            } else {
                v.vibrate(600L)
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

    private val allApps = ArrayList<AppEntry>()
    private val shown = ArrayList<AppEntry>()

    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var count: TextView
    private lateinit var selLabel: TextView
    private lateinit var status: TextView
    private lateinit var goalField: EditText
    private lateinit var rulesField: EditText
    private lateinit var api: EditText
    private lateinit var base: EditText
    private lateinit var model: EditText
    private lateinit var ivl: EditText
    private lateinit var maxL: EditText
    private lateinit var maxM: EditText
    private lateinit var maxS: EditText
    private lateinit var targetField: EditText
    private lateinit var metricField: EditText
    private lateinit var onlyGames: CheckBox
    private lateinit var dimBox: CheckBox
    private lateinit var keepBox: CheckBox
    private lateinit var autoBox: CheckBox
    private lateinit var modes: RadioGroup
    private lateinit var zones: RadioGroup
    private lateinit var adsG: RadioGroup
    private lateinit var gamesPane: LinearLayout
    private lateinit var setPane: ScrollView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(16, 16, 16, 16)

        root.addView(txt("Venice AutoPilot", 20f, true))

        val tabs = LinearLayout(this)
        val bGames = Button(this)
        bGames.text = "Список игр"
        val bSet = Button(this)
        bSet.text = "Настройки"
        tabs.addView(bGames, wlp(1f))
        tabs.addView(bSet, wlp(1f))
        root.addView(tabs)

        // ---------- ПАНЕЛЬ ИГР ----------
        gamesPane = LinearLayout(this)
        gamesPane.orientation = LinearLayout.VERTICAL

        search = EditText(this)
        search.hint = "поиск игры"
        search.setSingleLine(true)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        gamesPane.addView(search)

        onlyGames = CheckBox(this)
        onlyGames.text = "только игры"
        onlyGames.isChecked = true
        count = txt("", 12f, false)
        val rowFilter = LinearLayout(this)
        rowFilter.gravity = Gravity.CENTER_VERTICAL
        rowFilter.addView(onlyGames)
        rowFilter.addView(count)
        gamesPane.addView(rowFilter)

        list = ListView(this)
        list.adapter = Adapter()
        list.isFastScrollEnabled = true
        list.setPadding(0, 8, 0, 8)
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = shown[pos]
            val prev = sel
            if (prev != null && prev != e.pkg) {
                Mem.saveRules(this, prev, rulesField.text.toString())
                Mem.saveGoal(this, prev, goalField.text.toString())
            }
            sel = e.pkg
            selLabel.text = pick(e)
            goalField.setText(Mem.goal(this, e.pkg))
            rulesField.setText(Mem.rules(this, e.pkg))
            sp.edit().putString(P.LAST, e.pkg).apply()
            save()
            val a = list.adapter
            if (a is Adapter) a.notifyDataSetChanged()
        }
        gamesPane.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        selLabel = txt("Выбрано: —", 14f, true)
        gamesPane.addView(selLabel)

        status = txt("статус: не запущен", 11f, false)
        gamesPane.addView(status)

        val rowRun = LinearLayout(this)
        val bStart = Button(this)
        bStart.text = "СТАРТ"
        val bStop = Button(this)
        bStop.text = "СТОП"
        rowRun.addView(bStart, wlp(1f))
        rowRun.addView(bStop, wlp(1f))
        gamesPane.addView(rowRun)

        root.addView(gamesPane, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---------- ПАНЕЛЬ НАСТРОЕК ----------
        val setCol = LinearLayout(this)
        setCol.orientation = LinearLayout.VERTICAL

        val r1 = LinearLayout(this)
        val bAcc = Button(this)
        bAcc.text = "Спец. возможности"
        val bOvl = Button(this)
        bOvl.text = "Поверх окон"
        r1.addView(bAcc, wlp(1f))
        r1.addView(bOvl, wlp(1f))
        setCol.addView(r1)

        api = EditText(this)
        api.hint = "Venice API key"
        base = EditText(this)
        base.hint = "Base URL"
        model = EditText(this)
        model.hint = "vision-модель"
        setCol.addView(api)
        setCol.addView(base)
        setCol.addView(model)

        setCol.addView(txt("ЦЕЛЬ (словами, для выбранной игры):", 12f, true))
        goalField = EditText(this)
        goalField.hint = "напр.: прокачать метлу до 2000 метров, собрать 50k золота"
        setCol.addView(goalField)

        setCol.addView(txt("ПРАВИЛА ИГРЫ (для выбранной игры, сохраняется):", 12f, true))
        rulesField = EditText(this)
        rulesField.hint = "напр.: камни не собирать, хорошее кладу в ведро, опасное не трогаю"
        rulesField.gravity = Gravity.TOP or Gravity.START
        rulesField.minLines = 3
        rulesField.setSingleLine(false)
        setCol.addView(rulesField)

        val bTpl = Button(this)
        bTpl.text = "Пример правил"
        setCol.addView(bTpl)

        autoBox = CheckBox(this)
        autoBox.text = "сам прохожу меню, старт и туториал"
        autoBox.isChecked = true
        setCol.addView(autoBox)

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
        setCol.addView(modes)

        adsG = RadioGroup(this)
        val aSkip = RadioButton(this)
        aSkip.text = "реклама: пропускать сразу"
        aSkip.id = 21
        aSkip.isChecked = true
        val aWatch = RadioButton(this)
        aWatch.text = "реклама: смотреть за награду"
        aWatch.id = 22
        adsG.addView(aSkip)
        adsG.addView(aWatch)
        setCol.addView(adsG)

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
        setCol.addView(zones)

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
        setCol.addView(r3)

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
        setCol.addView(r4)

        val r4b = LinearLayout(this)
        r4b.gravity = Gravity.CENTER_VERTICAL
        r4b.addView(txt("ЦЕЛЬ ПО ЧИСЛУ (0 = выкл):", 14f, false))
        targetField = EditText(this)
        targetField.setText("0")
        targetField.width = 230
        r4b.addView(targetField)
        r4b.addView(txt("что за число:", 14f, false))
        metricField = EditText(this)
        metricField.hint = "метры / золото / монеты"
        metricField.width = 380
        r4b.addView(metricField)
        setCol.addView(r4b)

        val rowBtns = LinearLayout(this)
        val bTest = Button(this)
        bTest.text = "Тест API"
        val bRec = Button(this)
        bRec.text = "Запись макроса"
        rowBtns.addView(bTest, wlp(1f))
        rowBtns.addView(bRec, wlp(1f))
        setCol.addView(rowBtns)

        val bMem = Button(this)
        bMem.text = "Сбросить память игры"
        setCol.addView(bMem)

        setPane = ScrollView(this)
        setPane.addView(setCol)
        setPane.visibility = View.GONE
        root.addView(setPane, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        bGames.setOnClickListener {
            gamesPane.visibility = View.VISIBLE
            setPane.visibility = View.GONE
        }
        bSet.setOnClickListener {
            gamesPane.visibility = View.GONE
            setPane.visibility = View.VISIBLE
        }

        api.setText(sp.getString(P.API, ""))
        base.setText(sp.getString(P.BASE, P.DEF_BASE))
        model.setText(sp.getString(P.MODEL, P.DEF_MODEL))
        goalField.setText(sp.getString(P.GOAL, ""))
        rulesField.setText(sp.getString(P.RULES, ""))
        ivl.setText(sp.getInt(P.IVL, 900).toString())
        targetField.setText(sp.getInt(P.TARGET, 0).toString())
        metricField.setText(sp.getString(P.METRIC, ""))
        maxL.setText(sp.getInt("maxL", 0).toString())
        maxM.setText(sp.getInt("maxM", 0).toString())
        maxS.setText(sp.getInt("maxS", 0).toString())
        dimBox.isChecked = sp.getBoolean(P.DIM, true)
        keepBox.isChecked = sp.getBoolean(P.KEEP, true)
        autoBox.isChecked = sp.getBoolean(P.AUTO, true)
        onlyGames.isChecked = sp.getBoolean("onlyGames", true)
        adsG.check(if (sp.getInt(P.ADS, C.ADS_SKIP) == C.ADS_WATCH) 22 else 21)
        when (sp.getInt(P.MODE, C.MODE_AI)) {
            C.MODE_MACRO -> modes.check(2)
            C.MODE_HYBRID -> modes.check(3)
            C.MODE_SPEED -> modes.check(4)
            else -> modes.check(1)
        }
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
        bTpl.setOnClickListener {
            rulesField.setText(C.RULES_TEMPLATE)
            toast("Пример подставлен — правь под игру")
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
                Mem.saveRules(this, p, "")
                goalField.setText("")
                rulesField.setText("")
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
                    "шагов ${AutoPilotService.stepsLive}, уровней ${AutoPilotService.levelsLive}\nИИ: ${AutoPilotService.lastReply.takeLast(90)}"
                }
                h.postDelayed(this, 700)
            }
        }, 700)

        load()
    }

    private fun pick(e: AppEntry): String {
        val r = if (Mem.rules(this, e.pkg).isNotBlank()) " • правила есть" else ""
        return "Выбрано: ${e.label}$r"
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

    private fun currentAds(): Int = if (adsG.checkedRadioButtonId == 22) C.ADS_WATCH else C.ADS_SKIP

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        for (e in allApps) {
            if (q.isEmpty() || e.label.lowercase().contains(q) || e.pkg.lowercase().contains(q)) {
                shown.add(e)
            }
        }
        val a = list.adapter
        if (a is Adapter) a.notifyDataSetChanged()
        count.text = if (q.isEmpty()) "всего: ${allApps.size}"
        else "найдено: ${shown.size} из ${allApps.size}"
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
                allApps.clear()
                allApps.addAll(found)
                applyFilter()
                val last = sp.getString(P.LAST, null)
                if (last != null) {
                    val f = found.firstOrNull { it.pkg == last }
                    if (f != null) {
                        sel = f.pkg
                        selLabel.text = pick(f)
                        goalField.setText(Mem.goal(this, f.pkg))
                        rulesField.setText(Mem.rules(this, f.pkg))
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
        val t = targetField.text.toString().toIntOrNull() ?: 0
        if (t > 0 && metricField.text.toString().trim().isEmpty()) {
            toast("Напиши, что за число (метры, золото…)")
            return
        }
        save()
        Mem.saveGoal(this, p, goalField.text.toString())
        Mem.saveRules(this, p, rulesField.text.toString())
        pend = m
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1001)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1001 && res == RESULT_OK && data != null) {
            val g = goalField.text.toString()
            val p = sel
            if (p != null) {
                Mem.saveGoal(this, p, g)
                Mem.saveRules(this, p, rulesField.text.toString())
            }
            save()
            val i = Intent(this, AutoPilotService::class.java)
            i.action = C.START
            i.putExtra("code", res)
            i.putExtra("data", data)
            i.putExtra("pkg", p)
            i.putExtra("mode", pend)
            i.putExtra("interval", ivl.text.toString().toIntOrNull() ?: 900)
            i.putExtra("goal", g)
            i.putExtra("auto", autoBox.isChecked)
            i.putExtra("maxLevels", maxL.text.toString().toIntOrNull() ?: 0)
            i.putExtra("maxMinutes", maxM.text.toString().toIntOrNull() ?: 0)
            i.putExtra("maxSteps", maxS.text.toString().toIntOrNull() ?: 0)
            i.putExtra("target", targetField.text.toString().toIntOrNull() ?: 0)
            i.putExtra("metric", metricField.text.toString().trim())
            i.putExtra("ads", currentAds())
            i.putExtra("dim", dimBox.isChecked)
            i.putExtra("keepOn", keepBox.isChecked)
            i.putExtra("zone", currentZone())
            startForegroundService(i)
            toast("Бот запущен")
        } else if (req == 1001) {
            toast("Захват экрана не разрешён")
        }
    }

    override fun onPause() {
        super.onPause()
        try { save() } catch (e: Throwable) {}
        val p = sel
        if (p != null) {
            try {
                Mem.saveGoal(this, p, goalField.text.toString())
                Mem.saveRules(this, p, rulesField.text.toString())
            } catch (e: Throwable) {}
        }
    }

    private fun save() {
        sp.edit()
            .putString(P.API, api.text.toString().trim())
            .putString(P.BASE, base.text.toString().trim())
            .putString(P.MODEL, model.text.toString().trim())
            .putString(P.GOAL, goalField.text.toString())
            .putString(P.RULES, rulesField.text.toString())
            .putInt(P.IVL, ivl.text.toString().toIntOrNull() ?: 900)
            .putInt("maxL", maxL.text.toString().toIntOrNull() ?: 0)
            .putInt("maxM", maxM.text.toString().toIntOrNull() ?: 0)
            .putInt("maxS", maxS.text.toString().toIntOrNull() ?: 0)
            .putBoolean(P.DIM, dimBox.isChecked)
            .putBoolean(P.KEEP, keepBox.isChecked)
            .putBoolean(P.AUTO, autoBox.isChecked)
            .putBoolean("onlyGames", onlyGames.isChecked)
            .putInt(P.MODE, currentMode())
            .putInt(P.ADS, currentAds())
            .putInt(P.ZONE, currentZone())
            .putInt(P.TARGET, targetField.text.toString().toIntOrNull() ?: 0)
            .putString(P.METRIC, metricField.text.toString().trim())
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
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val e = shown[p]
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

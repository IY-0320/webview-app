package com.dreamland.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar

/**
 * 폰 알림 · 소리 · 진동 (드림랜드가 보낸 연락과 전화를 폰에 보여 준다).
 *
 * 알림 채널은 무음으로 만들고 소리 · 진동은 앱이 직접 낸다. 채널 소리는 한 번 만들면 앱이 못 바꿔서,
 * 이렇게 해야 설정에서 고른 소리(기본 소리 · 내 파일)를 바로 바꿔 쓸 수 있다.
 * 폰이 무음 · 진동 모드거나 방해 금지 중이면 그에 따른다.
 */
object Alerts {
    const val CH_MSG = "dl_msg"
    const val CH_MSG_QUIET = "dl_msg_quiet"
    const val CH_CALL = "dl_call"
    const val CH_MISSED = "dl_missed"
    const val CH_AWAKE = "dl_awake"

    const val ID_CALL = 7001
    const val ID_MISSED = 7002
    const val ID_AWAKE = 7003

    /** 알림을 누르면: 앱을 열고 드림랜드로 들어간다 */
    const val ACTION_OPEN = "com.dreamland.webview.OPEN"
    /** 전화 받기 · 문자로 답하기 (extra "do") */
    const val ACTION_CALL = "com.dreamland.webview.CALL"
    const val ACTION_DECLINE = "com.dreamland.webview.DECLINE"

    private const val PREFS = "dreamland"
    val PINK = Color.parseColor("#D98A9E")
    private val GOLD = Color.parseColor("#C9B27C")
    private val NIGHT = Color.parseColor("#14120F")

    val MSG_SOUNDS = linkedMapOf(
        "moonbell" to R.raw.msg_moonbell, "star" to R.raw.msg_star,
        "drop" to R.raw.msg_drop, "musicbox" to R.raw.msg_musicbox,
    )
    val RING_SOUNDS = linkedMapOf("dream" to R.raw.ring_dream, "calm" to R.raw.ring_calm)
    private val VIBES = mapOf(
        "tap" to longArrayOf(0, 70),
        "double" to longArrayOf(0, 60, 110, 60),
        "long" to longArrayOf(0, 450),
        "heart" to longArrayOf(0, 80, 120, 160),
    )
    private val RING_VIBE = longArrayOf(0, 900, 700, 900, 1500)

    private val main = Handler(Looper.getMainLooper())

    // ── 설정 ──

    class Cfg(p: SharedPreferences) {
        val msgOn = p.getBoolean("n_msg", true)
        val callOn = p.getBoolean("n_call", true)
        val awake = p.getBoolean("n_awake", false)
        /** both · sound · vibe · silent */
        val mode = p.getString("n_mode", "both") ?: "both"
        val sound = p.getString("n_sound", "moonbell") ?: "moonbell"
        val soundName = p.getString("n_sound_name", "") ?: ""
        val vibe = p.getString("n_vibe", "double") ?: "double"
        /** full · name · hide (잠금 화면에서 보이는 것) */
        val preview = p.getString("n_preview", "full") ?: "full"
        val banner = p.getBoolean("n_banner", true)
        val ring = p.getString("c_sound", "dream") ?: "dream"
        val ringName = p.getString("c_sound_name", "") ?: ""
        val ringVibe = p.getBoolean("c_vibe", true)
        /** 홈 화면 · 다른 앱을 쓰는 중에도 전화 화면을 바로 띄우기 (끄면 삼성 전화 알림) */
        val callFull = p.getBoolean("c_full", true)
        val quietOn = p.getBoolean("q_on", false)
        val quietFrom = p.getString("q_from", "23:00") ?: "23:00"
        val quietTo = p.getString("q_to", "07:00") ?: "07:00"
        val keepScreen = p.getBoolean("keep_screen", false)
        /** 다른 앱 위 팝업 (권한이 있을 때) */
        val popup = p.getBoolean("n_popup", true)
    }

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun cfg(c: Context) = Cfg(prefs(c))

    // ── 디자인 (skins.css): 기본 하나 + 캐릭터마다 따로 ──

    val SKINS = listOf("dream", "kakao", "line", "imsg", "samsung")

    /** kind = msg · call */
    fun skinDefault(c: Context, kind: String) =
        prefs(c).getString(if (kind == "call") "skin_call" else "skin_msg", "dream")?.takeIf { it in SKINS } ?: "dream"

    fun skinMap(c: Context): JSONObject = runCatching { JSONObject(prefs(c).getString("skin_map", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun skinFor(c: Context, name: String, kind: String): String =
        skinMap(c).optJSONObject(name)?.optString(kind)?.takeIf { it in SKINS } ?: skinDefault(c, kind)

    /** 연락이 온 캐릭터 (알림 꾸미기에서 캐릭터별 디자인을 고를 때 쓰는 목록). 최근 것이 앞, 30명까지 */
    fun seen(c: Context): JSONArray = runCatching { JSONArray(prefs(c).getString("seen", "[]") ?: "[]") }.getOrDefault(JSONArray())

    fun remember(c: Context, name: String, face: Bitmap?) {
        if (name.isBlank()) return
        val old = seen(c)
        var keepFace = ""
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("name") == name) keepFace = o.optString("face") else if (out.length() < 29) out.put(o)
        }
        val small = face?.let { b ->
            runCatching {
                val s = Bitmap.createScaledBitmap(b, 72, 72, true)
                val bo = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.PNG, 100, bo)
                "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
        } ?: keepFace
        val list = JSONArray().put(JSONObject().put("name", name).put("face", small))
        for (i in 0 until out.length()) list.put(out.get(i))
        prefs(c).edit().putString("seen", list.toString()).apply()
    }

    /** 내가 고른 소리 파일 (kind = msg · call) */
    fun customFile(c: Context, kind: String) = File(File(c.filesDir, "sounds").apply { mkdirs() }, kind)

    private fun minutes(s: String): Int {
        val p = s.split(':'); return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 조용한 시간: 알림은 오지만 소리 · 진동은 없다 (23:00 → 07:00 처럼 자정을 넘겨도 된다) */
    fun inQuiet(cfg: Cfg): Boolean {
        if (!cfg.quietOn) return false
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val a = minutes(cfg.quietFrom); val b = minutes(cfg.quietTo)
        return if (a == b) false else if (a < b) now in a until b else now >= a || now < b
    }

    // ── 채널 ──

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int, desc: String) = NotificationChannel(id, name, imp).apply {
            description = desc
            setSound(null, null)
            enableVibration(false)
            lightColor = PINK
            enableLights(imp >= NotificationManager.IMPORTANCE_DEFAULT)
        }
        nm.createNotificationChannels(listOf(
            ch(CH_MSG, "메시지", NotificationManager.IMPORTANCE_HIGH, "드림랜드 대화 모드에서 온 연락. 소리와 진동은 앱의 알림 꾸미기에서 바꿔요."),
            ch(CH_MSG_QUIET, "메시지 (팝업과 함께)", NotificationManager.IMPORTANCE_DEFAULT, "다른 앱 위 팝업을 띄웠을 때 알림창에 남기는 기록"),
            ch(CH_CALL, "전화", NotificationManager.IMPORTANCE_HIGH, "드림랜드 캐릭터에게서 걸려 온 전화"),
            ch(CH_MISSED, "부재중 전화", NotificationManager.IMPORTANCE_DEFAULT, "받지 못한 전화"),
            ch(CH_AWAKE, "깨어 있음", NotificationManager.IMPORTANCE_MIN, "앱을 나가 있어도 연락을 받는 동안 떠 있는 알림"),
        ))
    }

    fun canNotify(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** 잠금 화면 위 전화 화면을 띄울 수 있는지 (Android 14+ 는 사용자가 허락해야 한다) */
    fun canFullScreen(c: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    // ── 그림 ──

    /** "data:image/png;base64,..." → 동그랗게 자른 얼굴 */
    fun avatarFromData(data: String?): Bitmap? {
        if (data.isNullOrBlank() || !data.startsWith("data:image")) return null
        return runCatching {
            val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { circle(it) }
        }.getOrNull()
    }

    private fun circle(src: Bitmap, size: Int = 192): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val m = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - m) / 2, (src.height - m) / 2, m, m)
        val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p)
        return out
    }

    /** 얼굴 그림이 없을 때: 밤하늘색 원 + 금빛 테 + 이름 첫 글자 (드림랜드 첫 화면의 달 원처럼) */
    fun letterAvatar(name: String, size: Int = 192): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val r = size / 2f
        cv.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NIGHT })
        cv.drawCircle(r, r, r - size * .045f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = size * .03f; color = GOLD; alpha = 170
        })
        val ch = name.trim().codePoints().findFirst().let { if (it.isPresent) String(Character.toChars(it.asInt)) else "✦" }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PINK; textAlign = Paint.Align.CENTER; textSize = size * .44f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        cv.drawText(ch, r, r - (tp.descent() + tp.ascent()) / 2, tp)
        return out
    }

    // ── 소리 · 진동 ──

    private fun vibrator(c: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun vibrate(c: Context, pattern: LongArray, repeat: Int = -1) {
        val v = vibrator(c)
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val e = VibrationEffect.createWaveform(pattern, repeat)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, android.os.VibrationAttributes.createForUsage(
                if (repeat >= 0) android.os.VibrationAttributes.USAGE_RINGTONE else android.os.VibrationAttributes.USAGE_NOTIFICATION))
            else @Suppress("DEPRECATION") v.vibrate(e, AudioAttributes.Builder()
                .setUsage(if (repeat >= 0) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION).build())
        } else @Suppress("DEPRECATION") v.vibrate(pattern, repeat)
    }

    /** 폰 상태로 본 소리 · 진동 허용: (소리, 진동) */
    private fun allowed(c: Context): Pair<Boolean, Boolean> {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 23 && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false to false
        return when ((c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> false to false
            AudioManager.RINGER_MODE_VIBRATE -> false to true
            else -> true to true
        }
    }

    private var player: MediaPlayer? = null

    private fun stopPlayer() {
        player?.runCatching { stop(); release() }
        player = null
    }

    /** kind = msg · call, id = 기본 소리 이름 또는 "custom" */
    private fun play(c: Context, kind: String, id: String, loop: Boolean) {
        stopPlayer()
        val usage = if (kind == "call") AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION
        val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val mp = MediaPlayer()
        try {
            val own = customFile(c, kind)
            if (id == "custom" && own.length() > 0) mp.setDataSource(own.path)
            else {
                val res = (if (kind == "call") RING_SOUNDS[id] ?: R.raw.ring_dream else MSG_SOUNDS[id] ?: R.raw.msg_moonbell)
                c.resources.openRawResourceFd(res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            }
            mp.setAudioAttributes(attrs)
            mp.isLooping = loop
            mp.setOnCompletionListener { if (!loop) { it.release(); if (player === it) player = null } }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            mp.release()
        }
    }

    /** 메시지 한 번 울리기 (알림 방식 · 조용한 시간 · 폰 무음 모드를 따른다) */
    fun chime(c: Context) {
        val cfg = cfg(c)
        if (inQuiet(cfg)) return
        val (snd, vib) = allowed(c)
        if (snd && (cfg.mode == "both" || cfg.mode == "sound")) play(c, "msg", cfg.sound, false)
        if (vib && (cfg.mode == "both" || cfg.mode == "vibe" || (!snd && cfg.mode == "sound"))) vibrate(c, VIBES[cfg.vibe] ?: VIBES.getValue("double"))
    }

    /** 설정의 미리듣기 (폰 무음 모드와 상관없이 한 번) */
    fun preview(c: Context, kind: String, id: String) {
        if (kind == "vibe") { vibrate(c, VIBES[id] ?: return); return }
        play(c, kind, id, false)
    }

    fun stopPreview() = stopPlayer()

    // ── 메시지 알림 ──

    private class Said(val text: String, val at: Long)
    private val threads = LinkedHashMap<String, MutableList<Said>>()
    private val faces = HashMap<String, Bitmap>()

    private fun idFor(name: String) = 6000 + (name.hashCode() and 0x3ff)

    private fun openIntent(c: Context): PendingIntent = PendingIntent.getActivity(c, 1,
        Intent(c, MainActivity::class.java).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun person(name: String, face: Bitmap?) = Person.Builder().setName(name).setKey("dl:$name")
        .setIcon(IconCompat.createWithBitmap(face ?: letterAvatar(name))).build()

    fun message(c: Context, name0: String, body: String, faceData: String, skin: String? = null) =
        message(c, name0, listOf(body), faceData, skin)

    /**
     * 캐릭터마다 알림 하나. 같은 캐릭터의 연락은 그 알림에 쌓인다 (카톡처럼).
     * lines = 한 답장의 말풍선들: 하나씩 따로 쌓아서, 접혀 있으면 마지막 말풍선, 펼치면 주르륵 보인다
     */
    @SuppressLint("MissingPermission")
    fun message(c: Context, name0: String, lines0: List<String>, faceData: String, skin: String? = null) {
        val cfg = cfg(c)
        if (!cfg.msgOn) return
        val name = name0.ifBlank { "드림랜드" }
        val lines = lines0.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("새 연락이 왔어요") }
        val face = avatarFromData(faceData)
        // 다른 앱 위 팝업 (고른 디자인). 알림창 기록용 보통 알림도 함께 남긴다
        val popped = cfg.popup && Overlay.usable(c)
        if (popped)
            Overlay.show(c, JSONObject().put("skin", skin ?: skinFor(c, name, "msg")).put("name", name)
                .put("body", lines.joinToString("\n")).put("face", faceData))
        if (!canNotify(c)) { chime(c); return }
        ensureChannels(c)
        face?.let { faces[name] = it }
        val list = threads.getOrPut(name) { mutableListOf() }
        val t0 = System.currentTimeMillis()
        lines.forEachIndexed { i, l -> list.add(Said(l, t0 + i)) }
        while (list.size > 12) list.removeAt(0)
        val who = person(name, faces[name])
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("나").build())
        list.forEach { style.addMessage(it.text, it.at, who) }
        // 팝업을 띄웠으면 알림은 위에 튀어나오지 않는 채널로 (팝업을 가리지 않게)
        val b = NotificationCompat.Builder(c, if (popped) CH_MSG_QUIET else CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setColor(PINK)
            .setStyle(style)
            .setContentTitle(name)
            .setContentText(list.last().text)
            .setLargeIcon(faces[name] ?: letterAvatar(name))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setNumber(list.size)
            .setContentIntent(openIntent(c))
            .setDeleteIntent(PendingIntent.getBroadcast(c, idFor(name),
                Intent(c, AlertReceiver::class.java).setAction("forget").putExtra("name", name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        when (cfg.preview) {
            "full" -> b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            "name" -> b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(c, CH_MSG).setSmallIcon(R.drawable.ic_stat_moon).setColor(PINK)
                    .setContentTitle(name).setContentText("새 연락이 왔어요").build())
            else -> b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(c, CH_MSG).setSmallIcon(R.drawable.ic_stat_moon).setColor(PINK)
                    .setContentTitle("드림랜드").setContentText("새 연락이 왔어요").build())
        }
        runCatching { NotificationManagerCompat.from(c).notify(idFor(name), b.build()) }
        chime(c)
    }

    fun forget(name: String) { threads.remove(name) }

    /** 드림랜드를 다시 보면 쌓인 메시지 알림을 걷는다 */
    fun clearMessages(c: Context) {
        val nm = NotificationManagerCompat.from(c)
        threads.keys.forEach { nm.cancel(idFor(it)) }
        threads.clear()
    }

    // ── 전화 ──

    class Ring(val name: String, val face: Bitmap?, val faceData: String, val test: Boolean, val skin: String) {
        val at = System.currentTimeMillis()
    }

    @Volatile var ring: Ring? = null
        private set
    private var callNote: (() -> Unit)? = null

    /** 울리는 중이면 전화 알림을 띄운다 (전화 화면을 내리거나 앱을 나갔을 때) */
    fun showCallNote() { if (ring != null) callNote?.invoke() }
    /** 전화 화면이 듣는다: 울림이 끝나면 닫힌다 */
    var onRingEnd: (() -> Unit)? = null
    /** MainActivity(드림랜드 화면)가 듣는다: 알림의 "거절" */
    var onDecline: (() -> Unit)? = null
    private val ringGuard = Runnable { ring?.let { endRing(appContext ?: return@Runnable, missed = true) } }
    private var appContext: Context? = null

    private fun callIntent(c: Context, what: String, code: Int) = PendingIntent.getActivity(c, code,
        Intent(c, MainActivity::class.java).setAction(ACTION_CALL).putExtra("do", what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * 드림랜드에서 전화가 울리기 시작했다. foreground = 앱이 앞에 떠 있음 (전화 화면을 바로 연다).
     * 앱이 뒤에 있으면 전화 알림을 띄운다: 화면이 꺼져 있거나 잠겨 있으면 전체 화면으로 전화 화면이 뜨고,
     * 다른 앱을 쓰는 중이면 위에 받기 · 거절 단추가 달린 알림이 뜬다.
     */
    @SuppressLint("MissingPermission")
    fun startRing(c0: Context, name0: String, faceData: String, foreground: Boolean, test: Boolean = false, skin: String? = null) {
        val c = c0.applicationContext
        appContext = c
        val cfg = cfg(c)
        if (!cfg.callOn) return
        endRing(c, missed = false, quiet = true)
        val name = name0.ifBlank { "드림랜드" }
        val face = avatarFromData(faceData)
        val r = Ring(name, face, faceData, test, skin?.takeIf { it in SKINS } ?: skinFor(c, name, "call"))
        ring = r
        ensureChannels(c)
        val screen = PendingIntent.getActivity(c, 3, Intent(c, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(c, 4, Intent(c, AlertReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val who = person(name, face)
        fun build(callStyle: Boolean) = NotificationCompat.Builder(c, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setColor(PINK)
            .setContentTitle(name)
            .setContentText("드림랜드에서 걸려 온 전화")
            .setLargeIcon(face ?: letterAvatar(name))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .apply {
                if (callStyle) setStyle(NotificationCompat.CallStyle.forIncomingCall(who, decline, callIntent(c, "accept", 5)))
                else addAction(0, "거절", decline).addAction(0, "받기", callIntent(c, "accept", 5))
            }
            .build()
        callNote = {
            if (canNotify(c)) {
                val nm = NotificationManagerCompat.from(c)
                if (runCatching { nm.notify(ID_CALL, build(true)) }.isFailure) runCatching { nm.notify(ID_CALL, build(false)) }
            }
        }
        // 앱이 앞에 있으면 전화 화면만 (알림까지 띄우면 전화 화면 위를 가린다). 전화 화면을 내리면 그때 알림을 띄운다.
        // 다른 앱을 쓰는 중이어도 "전화 화면 바로 띄우기"를 켰고 다른 앱 위 표시 권한 · 깨어 있기가 있으면 전화 화면을 바로 연다
        // (안드로이드는 뒤에 있는 앱이 화면을 여는 걸 막지만, 다른 앱 위 표시 권한이 있으면 허락한다)
        val full = cfg.callFull && Overlay.allowed(c) && AwakeService.running && Overlay.usable(c)
        if (foreground || full || !canNotify(c)) runCatching { c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showCallNote() }
        else showCallNote()
        // 소리 · 진동
        if (!inQuiet(cfg)) {
            val (snd, vib) = allowed(c)
            if (snd) play(c, "call", cfg.ring, true)
            if (vib && cfg.ringVibe) vibrate(c, RING_VIBE, 1)
        }
        // 드림랜드가 끝을 알려 주지 못해도(화면이 멈춤 등) 2분 뒤에는 그친다
        main.removeCallbacks(ringGuard)
        main.postDelayed(ringGuard, if (test) 30_000 else 120_000)
    }

    /** 울림을 그친다. missed = 받지 못한 전화 알림을 남긴다 */
    @SuppressLint("MissingPermission")
    fun endRing(c0: Context, missed: Boolean, quiet: Boolean = false) {
        val c = c0.applicationContext
        val r = ring ?: return
        ring = null
        callNote = null
        main.removeCallbacks(ringGuard)
        stopPlayer()
        vibrator(c).cancel()
        NotificationManagerCompat.from(c).cancel(ID_CALL)
        onRingEnd?.invoke()
        if (missed && !quiet && !r.test && canNotify(c)) {
            ensureChannels(c)
            val n = NotificationCompat.Builder(c, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat_moon)
                .setColor(PINK)
                .setContentTitle("부재중 전화")
                .setContentText(r.name)
                .setLargeIcon(r.face ?: letterAvatar(r.name))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setWhen(r.at)
                .setShowWhen(true)
                .setContentIntent(openIntent(c))
                .build()
            runCatching { NotificationManagerCompat.from(c).notify(ID_MISSED, n) }
        }
    }

    /** 받기 · 문자로: 드림랜드 화면(MainActivity)을 앞으로 부르고 그쪽에서 드림랜드의 단추를 누른다 */
    fun answer(c: Context, what: String, done: () -> Unit = {}) {
        val test = ring?.test == true
        onRingEnd = null   // 전화 화면은 잠금을 푼 뒤 스스로 닫는다 (done)
        endRing(c, missed = false, quiet = true)
        if (test) return done()
        val i = Intent(c, MainActivity::class.java).setAction(ACTION_CALL).putExtra("do", what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val km = c.getSystemService(KeyguardManager::class.java)
        if (c is android.app.Activity && km.isKeyguardLocked && Build.VERSION.SDK_INT >= 26) {
            // 잠겨 있으면 잠금을 푼 뒤 드림랜드로 간다 (풀지 않고 돌아오면 전화는 드림랜드에서 그대로 울리다 끊긴다)
            km.requestDismissKeyguard(c, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { c.startActivity(i); done() }
                override fun onDismissCancelled() = done()
                override fun onDismissError() = done()
            })
        } else { c.startActivity(i); done() }
    }

    fun decline(c: Context) {
        val test = ring?.test == true
        endRing(c, missed = false, quiet = true)
        if (!test) onDecline?.invoke()
    }
}

/** 알림의 "거절" 단추 · 알림을 밀어서 지웠을 때 (이 앱 안에서만 받는다) */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Alerts.ACTION_DECLINE -> Alerts.decline(c)
            "forget" -> Alerts.forget(i.getStringExtra("name") ?: return)
        }
    }
}

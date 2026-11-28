package com.dreamland.webview

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 다른 앱 위에 뜨는 메시지 팝업 (예전 카톡 팝업처럼). "다른 앱 위에 표시" 권한 + "앱을 나가도 연락 받기"가 켜져 있을 때만.
 * 홈 화면 · 다른 앱을 쓰는 중에 연락이 오면 고른 디자인의 카드가 위에서 내려왔다가 5초 뒤 사라진다.
 * 누르면 드림랜드로, 위로 밀면 닫힘. 잠금 화면 · 꺼진 화면에서는 띄우지 않는다 (그때는 보통 알림만).
 *
 * 처음에는 웹 화면(popup.html)으로 그렸는데, 앱이 뒤에 있을 때 웹 화면이 그려지지 않는 일이 잦아서(S22)
 * 안드로이드 화면 부품으로 그린다. 모양은 skins.css 의 메시지 카드(.mc) 다섯 가지와 같게 맞춘다.
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private val hideLater = Runnable { hide() }

    fun allowed(c: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

    /** 지금 띄워도 되는지: 권한 있음 · 앱이 깨어 있음(AwakeService) · 화면 켜짐 · 잠금 풀림 */
    fun usable(c: Context): Boolean {
        if (!allowed(c) || !AwakeService.running) return false
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = c.getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    /** o = {skin, name, body, face} */
    fun show(c0: Context, o: JSONObject) = main.post {
        val c = c0.applicationContext
        if (!usable(c)) return@post
        removeNow(c)
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val top = c.resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) c.resources.getDimensionPixelSize(it) else dp(c, 24) }
        val card = Skin.card(c, o.optString("skin"), o.optString("name").ifBlank { "드림랜드" }, o.optString("body").ifBlank { "새 연락이 왔어요" }, o.optString("face"))
        val r = FrameLayout(c).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 16))
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(-1, -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = top; windowAnimations = android.R.style.Animation_Dialog }
        if (runCatching { wm.addView(r, p) }.isFailure) return@post
        root = r
        // 위에서 미끄러져 내려오기는 창 자체 애니메이션(안드로이드 토스트 방식)으로 한다.
        // 앱이 뒤에 있으면 앱 안의 애니메이션이 첫 장면(투명 · 화면 밖)에서 멈춰서 팝업이 안 보였다 (S22)
        card.translationY = 0f; card.alpha = 1f
        // 누르면 드림랜드로, 위로 밀면 닫힘
        var y0 = 0f; var moved = false
        @SuppressLint("ClickableViewAccessibility")
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; moved = false; main.removeCallbacks(hideLater) }
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - y0; if (dy < -8) { moved = true; v.translationY = dy } }
                MotionEvent.ACTION_UP -> {
                    if (moved && e.rawY - y0 < -dp(c, 24)) hide()
                    else if (!moved) open(c)
                    else { v.animate().translationY(0f).setDuration(200).start(); main.postDelayed(hideLater, 3000) }
                }
                MotionEvent.ACTION_CANCEL -> { v.translationY = 0f; main.postDelayed(hideLater, 3000) }
            }
            true
        }
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, 5000)
    }

    private fun open(c: Context) {
        removeNow(c)
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java).setAction(Alerts.ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** 위로 올라가며 사라진다 */
    fun hide() {
        main.post { slideAway() }
    }

    private fun slideAway() {
        val r = root ?: return
        main.removeCallbacks(hideLater)
        // 창을 떼면 창 애니메이션으로 사라진다 (앱 안 애니메이션은 뒤에 있을 때 멈출 수 있어서 쓰지 않음)
        removeNow(r.context)
    }

    private fun removeNow(c: Context) {
        val r = root ?: return
        root = null
        main.removeCallbacks(hideLater)
        runCatching { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
    }

    fun dp(c: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
}

/** 메시지 카드 다섯 가지 (skins.css 의 .mc 와 같은 모양) */
private object Skin {
    private class Look(
        val bg: Int, val radius: Int, val stroke: Int = 0,
        val name: Int, val body: Int,
        val app: String?, val appColor: Int = 0, val timeColor: Int = 0, val icon: Int = 0, val iconDot: Int = 0, val iconRound: Boolean = false,
        val header: Int = 0,
        val face: Int, val faceText: Int, val faceRadius: Float, val faceSize: Int = 44, val faceStroke: Int = 0,
        val timeAfterName: Boolean = false, val appRight: Boolean = false, val mono: Boolean = false,
    )

    private val LOOKS = mapOf(
        "dream" to Look(bg = c("#F6F0E3"), radius = 14, stroke = c("#59C9B27C"), name = c("#2A251D"), body = c("#7D7364"),
            app = "DREAMLAND", appColor = c("#D98A9E"), timeColor = c("#D98A9E"), appRight = true, mono = true,
            face = c("#14120F"), faceText = c("#E8A2B2"), faceRadius = .5f, faceStroke = c("#B3C9B27C")),
        "kakao" to Look(bg = Color.WHITE, radius = 16, name = c("#191919"), body = c("#555555"),
            app = "드림톡", appColor = c("#555555"), timeColor = c("#999999"), icon = c("#FEE500"), iconDot = c("#3A1D1D"),
            face = c("#A8C6E0"), faceText = Color.WHITE, faceRadius = .38f),
        "line" to Look(bg = Color.WHITE, radius = 12, name = c("#111111"), body = c("#666666"),
            app = "DREAM", appColor = Color.WHITE, timeColor = c("#D9FFFFFF"), icon = Color.WHITE, iconDot = c("#06C755"), header = c("#06C755"),
            face = c("#D7E8DC"), faceText = c("#2B7A47"), faceRadius = .5f),
        "imsg" to Look(bg = c("#F5F2F2F5"), radius = 22, name = Color.BLACK, body = c("#1C1C1E"),
            app = null, timeColor = c("#8A8A8E"), timeAfterName = true,
            face = c("#959AA6"), faceText = Color.WHITE, faceRadius = .5f, faceSize = 38),
        "samsung" to Look(bg = c("#FCFCFF"), radius = 26, name = c("#111111"), body = c("#555555"),
            app = "메시지", appColor = c("#3E91FF"), timeColor = c("#8C8C8C"), icon = c("#3E91FF"), iconDot = Color.WHITE, iconRound = true,
            face = c("#C8D9F5"), faceText = c("#2F63B8"), faceRadius = .5f),
    )

    private fun c(s: String) = Color.parseColor(s)

    fun card(ctx: Context, skin: String, name: String, body: String, faceData: String): View {
        val k = LOOKS[skin] ?: LOOKS.getValue("dream")
        fun dp(v: Int) = Overlay.dp(ctx, v)
        fun dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
        val bg = GradientDrawable().apply { setColor(k.bg); cornerRadius = dpf(k.radius.toFloat()); if (k.stroke != 0) setStroke(dp(1), k.stroke) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            elevation = dpf(10f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            val side = if (skin == "samsung") 18 else if (skin == "imsg") 15 else 14
            setPadding(dp(side), if (k.header != 0) 0 else dp(12), dp(side), dp(13))
        }
        // 앱 줄 (아이콘 · 이름 · 지금)
        if (k.app != null) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (k.header != 0) { setBackgroundColor(k.header); setPadding(dp(14), dp(7), dp(14), dp(7)) }
            }
            if (k.icon != 0) {
                val ic = FrameLayout(ctx).apply {
                    background = GradientDrawable().apply { setColor(k.icon); cornerRadius = if (k.iconRound) dpf(8f) else dpf(5f) }
                    addView(View(ctx).apply { background = GradientDrawable().apply { setColor(k.iconDot); cornerRadius = dpf(4f) } },
                        FrameLayout.LayoutParams(dp(9), dp(7), Gravity.CENTER))
                }
                row.addView(ic, LinearLayout.LayoutParams(dp(if (k.iconRound) 14 else 16), dp(if (k.iconRound) 14 else 16)).apply { marginEnd = dp(6) })
            }
            val app = TextView(ctx).apply {
                text = if (k.appRight) "${k.app} · 지금" else k.app
                setTextColor(k.appColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.mono) 9.5f else 11.5f)
                typeface = if (k.mono) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
                if (k.mono) letterSpacing = .2f
            }
            if (k.appRight) row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            row.addView(app)
            if (!k.appRight) {
                row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                row.addView(TextView(ctx).apply { text = if (skin == "samsung") "· 지금" else "지금"; setTextColor(k.timeColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f) })
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            if (k.header != 0) { lp.marginStart = -dp(14); lp.marginEnd = -dp(14); lp.bottomMargin = dp(10) } else lp.bottomMargin = dp(if (k.appRight) 2 else 8)
            card.addView(row, lp)
        }
        // 얼굴 + 이름 · 내용
        val main = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val size = dp(k.faceSize)
        val face = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(k.face); cornerRadius = size * k.faceRadius; if (k.faceStroke != 0) setStroke(dp(1), k.faceStroke) }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, size * k.faceRadius) }
            clipToOutline = true
        }
        val bmp = square(faceData)
        if (bmp != null) face.addView(ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        else face.addView(TextView(ctx).apply {
            text = name.trim().codePoints().findFirst().let { if (it.isPresent) String(Character.toChars(it.asInt)) else "✦" }
            setTextColor(k.faceText); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.faceSize < 44) 16f else 19f); gravity = Gravity.CENTER
            if (skin == "dream") typeface = Typeface.SERIF
        }, FrameLayout.LayoutParams(-1, -1))
        main.addView(face, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) })
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(TextView(ctx).apply {
            text = name; setTextColor(k.name); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (k.timeAfterName) nameRow.addView(TextView(ctx).apply { text = "지금"; setTextColor(k.timeColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(8), 0, 0, 0) })
        tx.addView(nameRow)
        tx.addView(TextView(ctx).apply {
            text = body; setTextColor(k.body); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (skin == "imsg") 14f else 13.5f)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        main.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(main)
        return card
    }

    private fun square(data: String?): Bitmap? {
        if (data.isNullOrBlank() || !data.startsWith("data:image")) return null
        return runCatching {
            val b = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }.getOrNull()
    }
}


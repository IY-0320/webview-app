package com.dreamland.webview

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/**
 * 드림랜드 캐릭터에게서 걸려 온 전화 화면 (잠금 화면 위에도 뜬다).
 * 모양은 앱 첫 화면처럼 밤하늘 · 금빛 테 · 분홍 달 (assets/call.html).
 * 받기 · 문자로 = 드림랜드 화면으로 가서 드림랜드 전화 화면의 그 단추를 누른다 / 거절 = 그 자리에서 거절.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var ready = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = Alerts.ring
        if (r == null) { finish(); return }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#0C0C0B")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#0C0C0B")

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0C0C0B"))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(Bridge(), "Call")
            webViewClient = object : WebViewClient() {
                // 앱 안의 전화 화면 파일만 연다
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !request.url.toString().startsWith("file:///android_asset/")
                override fun onPageFinished(view: WebView, url: String) { ready = true; fill() }
            }
        }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val d = resources.displayMetrics.density
            if (ready) web.evaluateJavascript("window.call && call.insets(${(b.top / d).toInt()}, ${(b.bottom / d).toInt()})", null)
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        // 뒤로가기: 전화 화면만 내린다 (울림은 알림으로 계속)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAndRemoveTask()
        })
        Alerts.onRingEnd = { runOnUiThread { if (!isFinishing) finishAndRemoveTask() } }
        web.loadUrl("file:///android_asset/call.html")
    }

    private fun fill() {
        val r = Alerts.ring ?: return finish()
        val o = JSONObject().put("name", r.name).put("face", r.faceData).put("at", r.at).put("test", r.test).put("skin", r.skin)
        web.evaluateJavascript("window.call && call.set($o)", null)
        // 위 · 아래 막대를 디자인 바탕색에 맞춘다
        val (top, bottom) = when (r.skin) {
            "kakao" -> "#3B3D45" to "#3B3D45"
            "line" -> "#12D16A" to "#058A3E"
            "imsg" -> "#1C1C1E" to "#1C1C1E"
            "samsung" -> "#263A5A" to "#2C7A74"
            else -> "#0C0C0B" to "#0C0C0B"
        }
        @Suppress("DEPRECATION") run {
            window.statusBarColor = Color.parseColor(top)
            window.navigationBarColor = Color.parseColor(bottom)
        }
        ViewCompat.requestApplyInsets(web)
    }

    // 전화 화면을 내리거나(뒤로 · 홈) 다른 앱으로 가면: 아직 울리는 중이면 전화 알림으로 남긴다
    override fun onStop() {
        super.onStop()
        Alerts.showCallNote()
    }

    override fun onDestroy() {
        if (Alerts.onRingEnd != null) Alerts.onRingEnd = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun accept() = runOnUiThread { Alerts.answer(this@CallActivity, "accept") { finish() } }

        @JavascriptInterface
        fun text() = runOnUiThread { Alerts.answer(this@CallActivity, "text") { finish() } }

        @JavascriptInterface
        fun decline() = runOnUiThread { Alerts.decline(this@CallActivity); finishAndRemoveTask() }
    }
}

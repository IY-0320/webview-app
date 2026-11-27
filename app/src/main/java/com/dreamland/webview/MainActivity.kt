package com.dreamland.webview

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.IntentFilter
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 화면 두 장:
 *  - startView : 앱 안의 첫 화면 (assets/start.html, "꿈속으로"). 터묵스를 깨우는 다리(Dream)는 여기에만 붙인다.
 *  - dreamView : 드림랜드 서버 화면. 다리를 붙이지 않는다 (서버 쪽 페이지가 앱 기능을 부르지 못하게).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        // 드림랜드 server.js 의 기본 포트. (8000 은 실리태번 기본 포트라 겹치면 실리태번이 열린다)
        const val DEFAULT_PORT = 4173
        const val DEFAULT_FOLDER = "~/dreamland"

        // 앱 아이콘을 길게 눌러 "설정"을 고르면 오는 신호
        const val ACTION_SETTINGS = "com.dreamland.webview.SETTINGS"

        private const val START_PAGE = "file:///android_asset/start.html"

        private const val PREFS = "dreamland"
        private const val KEY_URL = "server_url"          // 예전 판: 주소 전체를 저장했다
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_FOLDER = "folder"
        private const val KEY_SHOW_TERMUX = "show_termux"
        private const val KEY_WAKE_LOCK = "wake_lock"
        private const val KEY_ONBOARDED = "onboarded"    // 처음 질문(이미 쓰는지 / 처음인지)에 답했는지
        private const val KEY_SETUP_DONE = "setup_done"  // 설치 안내 02(외부 앱 허용)를 했다고 표시했는지

        // 드림랜드 README 의 Termux 설치 순서를 그대로 따른다
        private const val DREAMLAND_REPO = "https://github.com/geminibug2026/DreamLand.git"
        // 설치 안내에서 열 수 있는 주소 (그 밖의 주소는 열지 않는다)
        private val GUIDE_URLS = setOf(
            "https://f-droid.org/packages/com.termux/",
            "https://github.com/termux/termux-app/releases/latest",   // 정식판 (베타는 목록 맨 위에 있어 헷갈림)
            "https://github.com/geminibug2026/DreamLand",
        )

        private const val TERMUX_PKG = "com.termux"
        private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        const val SETUP_COMMAND =
            "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"

        private const val POLL_MS = 700L
        private const val SLOW_MS = 12_000L     // 이만큼 지나도 안 켜지면 "설정 확인" 안내를 띄운다
        private const val WAKE_TIMEOUT_MS = 90_000L
        private const val SLEEP_TIMEOUT_MS = 15_000L
        private const val INSTALL_TIMEOUT_MS = 20 * 60_000L   // 처음 설치는 pkg update 때문에 몇 분 걸린다
        private const val INSTALL_SLOW_MS = 60_000L

        private val NIGHT = Color.parseColor("#0C0C0B")

        /**
         * 드림랜드 화면의 맨 아래 · 맨 위 가운데에 보이는 배경색을 "아래|위" 로 돌려준다.
         * 투명한 요소는 건너뛰고 부모로 올라가며, 끝내 못 찾으면 드림랜드가 넣는 theme-color 를 쓴다.
         * (읽기만 하고 페이지에는 아무것도 하지 않는다)
         */
        private const val BAR_COLOR_JS = """(function(){
  function pick(y){
    var el = document.elementFromPoint(innerWidth / 2, y);
    while (el) {
      var c = getComputedStyle(el).backgroundColor, m = c.match(/[\d.]+/g);
      if (m && m.length >= 3 && (m.length < 4 || +m[3] > 0.6)) return c;
      el = el.parentElement;
    }
    var meta = document.querySelector('meta[name="theme-color"]');
    return meta ? meta.content : '';
  }
  return pick(innerHeight - 2) + '|' + pick(2);
})()"""
    }

    private enum class Probe { UP, KEY, OTHER, DOWN }

    private lateinit var startView: WebView
    private lateinit var dreamView: WebView
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var startReady = false
    private var firstCheck = true            // 앱을 처음 켰을 때만, 서버가 깨어 있으면 바로 들어간다
    private var openSettingsWhenReady = false
    private var waking = false
    private var wakeStartedAt = 0L
    private var installing = false           // waking 과 함께 켜진다: 설치 + 켜기를 기다리는 중
    private var installAfterPermission = false
    private var sleeping = false
    private var sleepStartedAt = 0L
    private var dreamLoadedUrl: String? = null
    private var clearHistoryWhenLoaded = false

    /** 이 주소가 지금 설정한 드림랜드 서버의 페이지인지 */
    private fun onServer(u: String?): Boolean {
        if (u == null) return false
        val a = Uri.parse(u)
        val b = Uri.parse(serverUrl)
        fun port(x: Uri) = if (x.port > 0) x.port else if (x.scheme == "https") 443 else 80
        return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }
    private var wakeAfterPermission = false
    private var lastState: JSONObject? = null
    private var insetsJs: String? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // ── 설정 (폰마다 저장) ──

    private var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) = prefs.edit().putString(KEY_HOST, v).apply()
    private var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()
    private var folder: String
        get() = prefs.getString(KEY_FOLDER, DEFAULT_FOLDER) ?: DEFAULT_FOLDER
        set(v) = prefs.edit().putString(KEY_FOLDER, v).apply()
    private var showTermux: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TERMUX, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_TERMUX, v).apply()
    private var wakeLock: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_WAKE_LOCK, v).apply()
    private var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDED, v).apply()
    private var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_SETUP_DONE, v).apply()

    /** host 칸에 "https://..." 처럼 적었으면 그 방식을 쓰고, 아니면 http */
    private val serverUrl: String
        get() {
            val h = host.trim().trimEnd('/')
            val scheme = if (h.contains("://")) h.substringBefore("://") else "http"
            val name = h.substringAfter("://").substringBefore('/')
            return "$scheme://$name:$port"
        }

    private val isLocal: Boolean
        get() = host.substringAfter("://").substringBefore('/').trim('[', ']') in
            setOf("127.0.0.1", "localhost", "::1")

    /** 예전 판은 "http://127.0.0.1:4173" 같은 주소 전체를 저장했다. 주소와 포트로 나눠 옮긴다 */
    private fun migrateOldUrl() {
        if (prefs.contains(KEY_HOST) || !prefs.contains(KEY_URL)) return
        val old = prefs.getString(KEY_URL, null) ?: return
        val uri = Uri.parse(if (old.contains("://")) old else "http://$old")
        val h = uri.host ?: DEFAULT_HOST
        // 1.0.1 의 잘못된 기본값(:8000)은 드림랜드 기본 포트로 고친다
        val p = if (h == DEFAULT_HOST && uri.port == 8000) DEFAULT_PORT
                else if (uri.port > 0) uri.port else DEFAULT_PORT
        host = if (uri.scheme == "https") "https://$h" else h
        port = p
        prefs.edit().remove(KEY_URL).apply()
    }

    // ── 권한 · 파일 ──

    private val permissionAsk =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            js("dl.onPermission && dl.onPermission($granted)")
            if (wakeAfterPermission) {
                wakeAfterPermission = false
                if (granted) wakeTermux() else pushState("error", "code" to "permission")
            }
            if (installAfterPermission) {
                installAfterPermission = false
                if (granted) installDreamland() else pushState("error", "code" to "permission")
            }
        }

    // 드림랜드가 한 개만 고르라고 했으면 한 개, 여러 개면 여러 개.
    // 종류(accept)로는 거르지 않는다: 폰마다 json · zip 을 다르게 불러서 못 고르는 파일이 생긴다 (드림랜드가 받은 뒤 다시 거른다)
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            fileCallback?.onReceiveValue(uris.toTypedArray())
            fileCallback = null
        }
    private val onePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileCallback?.onReceiveValue(uri?.let { arrayOf(it) } ?: emptyArray())
            fileCallback = null
        }

    private lateinit var saver: Saver
    private lateinit var root: FrameLayout

    // ── 화면 ──

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateOldUrl()
        ContextCompat.registerReceiver(this, resultReceiver, IntentFilter(resultAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        // 디버그 빌드에서만 크롬 개발자 도구로 웹뷰를 들여다볼 수 있게 한다 (배포판은 꺼짐)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            WebView.setWebContentsDebuggingEnabled(true)
        // 이 판 전부터 쓰던 사람(설정이 이미 저장돼 있음)에게는 처음 질문을 묻지 않는다
        if (!prefs.contains(KEY_ONBOARDED) && prefs.contains(KEY_HOST)) onboarded = true
        window.decorView.setBackgroundColor(Color.parseColor("#0C0C0B"))

        dreamView = WebView(this).apply {
            visibility = View.GONE
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                // 드림랜드는 http 로만 연다. 파일 올리기는 파일 선택 창(content://)을 쓰므로 폰 파일(file://) 접근은 막는다
                allowFileAccess = false
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = object : WebViewClient() {
                // 드림랜드가 도중에 꺼지면 첫 화면으로 돌아가서 다시 깨울 수 있게 한다
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    dreamLoadedUrl = null
                    showStart()
                    checkServer(auto = false)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    saver.inject()
                    // 새로 들어간 드림랜드가 다 뜬 뒤에 지난 기록을 지운다.
                    // (불러오기 직후에 지우면 아직 앞 페이지(about:blank)가 현재라 그게 기록에 남아,
                    //  뒤로 가면 하얀 화면이 나왔다)
                    if (clearHistoryWhenLoaded && onServer(url)) {
                        clearHistoryWhenLoaded = false
                        view.clearHistory()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) filePicker.launch("*/*")
                    else onePicker.launch("*/*")
                    return true
                }
            }
        }

        // 드림랜드의 내보내기 · 백업 받기 → 다운로드/Dreamland
        saver = Saver(this, dreamView) { serverUrl }.also { it.attach() }

        startView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0C0C0B"))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(Bridge(), "Dream")
            webViewClient = object : WebViewClient() {
                // 첫 화면은 앱 안의 파일만 연다
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !request.url.toString().startsWith("file:///android_asset/")
            }
        }

        root = FrameLayout(this)
        root.addView(dreamView, FrameLayout.LayoutParams(-1, -1))
        root.addView(startView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // 드림랜드 화면은 상태 표시줄 밑에서 시작한다. 드림랜드는 아래쪽 여백(safe-area)은 스스로 두지만
        // 위쪽은 페이지마다 달라서 글자가 시계 · 배터리와 겹친다. 비는 띠는 root 바탕색(= 화면 맨 위 색)으로 칠한다
        ViewCompat.setOnApplyWindowInsetsListener(dreamView) { v, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != top) { lp.topMargin = top; v.layoutParams = lp }
            // 나머지(아래 막대 높이)는 웹뷰에 그대로 넘겨서 드림랜드의 safe-area 여백이 계속 맞게 한다
            ViewCompat.onApplyWindowInsets(v, insets.inset(0, top, 0, 0))
        }
        startChrome(true)

        // 상태 표시줄 · 아래 막대 높이를 첫 화면에 알려서 버튼이 그 밑에 숨지 않게 한다
        ViewCompat.setOnApplyWindowInsetsListener(startView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val d = resources.displayMetrics.density
            insetsJs = "dl.onInsets(${(bars.top / d).toInt()}, ${(bars.bottom / d).toInt()})"
            if (startReady) js(insetsJs!!)
            ViewCompat.onApplyWindowInsets(v, insets)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // 드림랜드 안의 이전 화면으로. 그 앞이 드림랜드가 아니면(빈 페이지 등) 첫 화면으로 간다
                    dreamView.visibility == View.VISIBLE && dreamView.canGoBack() &&
                        onServer(dreamView.copyBackForwardList().let { it.getItemAtIndex(it.currentIndex - 1)?.url }) ->
                        dreamView.goBack()
                    // 드림랜드의 맨 앞 화면에서 뒤로 → 첫 화면(꿈속으로)
                    dreamView.visibility == View.VISIBLE -> { showStart(); checkServer(auto = false) }
                    // 첫 화면: 열린 시트가 있으면 닫고, 없으면 앱을 나간다
                    else -> startView.evaluateJavascript("window.dl ? dl.back() : false") { r ->
                        if (r != "true") finish()
                    }
                }
            }
        })

        if (intent?.action == ACTION_SETTINGS) {
            firstCheck = false
            openSettingsWhenReady = true
        }
        startView.loadUrl(START_PAGE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SETTINGS) {
            showStart()
            if (startReady) js("dl.openSettings()") else openSettingsWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        // 터묵스에 다녀왔을 때: 첫 화면이면 상태를 다시 본다
        if (startReady && !waking && !sleeping && startView.visibility == View.VISIBLE) checkServer(auto = false)
        // 터묵스를 깔고 오거나 권한을 바꾸고 왔을 수 있으니 설치 안내의 상태 표시를 새로 고친다
        if (startReady) js("dl.refreshCfg && dl.refreshCfg()")
        if (dreamView.visibility == View.VISIBLE) { main.removeCallbacks(barSync); main.post(barSync) }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(barSync)   // 안 보일 때는 막대 색을 읽지 않는다
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        saver.shutdown()
        runCatching { unregisterReceiver(resultReceiver) }
        super.onDestroy()
    }

    private fun showStart() {
        startView.visibility = View.VISIBLE
        dreamView.visibility = View.GONE
        startChrome(true)
        js("dl.enterDone && dl.enterDone()")
    }

    /**
     * 위 · 아래 시스템 막대를 화면에 맞춘다.
     *  - 첫 화면: 밤하늘색 막대 · 밝은 아이콘
     *  - 드림랜드: 드림랜드 화면 맨 아래 · 맨 위에 실제로 보이는 색을 1초마다 읽어 따라간다 (테마를 바꿔도 따라감)
     * 아래 막대 자리는 창 바탕(decorView)이 비쳐 보이므로 그 바탕색을 바꾸고,
     * 시스템이 따로 막(scrim)을 깔지 않게 대비 보정은 끈다. 아이콘 밝기는 색의 밝기로 정한다.
     */
    private fun startChrome(start: Boolean) {
        main.removeCallbacks(barSync)
        if (start) {
            applyBars(NIGHT, NIGHT)
        } else {
            lastBars = null
            main.post(barSync)
        }
    }

    private var lastBars: String? = null

    private val barSync: Runnable = object : Runnable {
        override fun run() {
            if (dreamView.visibility != View.VISIBLE) return
            dreamView.evaluateJavascript(BAR_COLOR_JS) { raw ->
                val s = raw?.trim('"') ?: ""
                if (s.isNotEmpty() && s != lastBars) {
                    val parts = s.split('|')
                    val bottom = parseCssColor(parts.getOrNull(0))
                    val top = parseCssColor(parts.getOrNull(1)) ?: bottom
                    if (bottom != null && top != null) {
                        lastBars = s
                        applyBars(bottom, top)
                    }
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyBars(bottom: Int, top: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        window.navigationBarColor = bottom
        window.statusBarColor = top   // Android 14 이하: 상태 표시줄이 따로 있어서 그 색도 맞춘다 (15 는 무시됨)
        window.decorView.setBackgroundColor(bottom)
        root.setBackgroundColor(top)   // 드림랜드 화면 위 · 상태 표시줄 자리의 띠
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = isLight(bottom)
            isAppearanceLightStatusBars = isLight(top)
        }
    }

    private fun isLight(c: Int): Boolean {
        val l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        return l > 150
    }

    /** "rgb(1, 2, 3)" · "rgba(...)" · "#fbf5f2" 를 색으로 */
    private fun parseCssColor(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return try {
            if (t.startsWith("#")) Color.parseColor(if (t.length == 4) "#" + t.drop(1).map { "$it$it" }.joinToString("") else t)
            else {
                val n = Regex("[\\d.]+").findAll(t).map { it.value.toFloat() }.toList()
                if (n.size < 3) null else Color.rgb(n[0].toInt(), n[1].toInt(), n[2].toInt())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun enterDream(key: String? = null) {
        val url = serverUrl
        js("dl.entering && dl.entering()")
        // 지금 드림랜드 화면이 드림랜드가 아니면(빈 페이지 · 오류 페이지) 새로 불러온다
        if (dreamLoadedUrl != url || !onServer(dreamView.url)) {
            clearHistoryWhenLoaded = true
            dreamView.loadUrl(
                if (key != null) "$url/?key=" + URLEncoder.encode(key, "UTF-8") else url
            )
            dreamLoadedUrl = url
        }
        // 짧게 들어가는 느낌을 준 뒤 드림랜드를 보여 준다
        main.postDelayed({
            if (dreamLoadedUrl == null) return@postDelayed   // 그 사이 불러오기에 실패했다
            dreamView.visibility = View.VISIBLE
            startView.visibility = View.GONE
            startChrome(false)
        }, 380)
    }

    // ── 서버 살펴보기 ──

    private fun probe(url: String): Probe {
        return try {
            val c = URL("$url/").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 3000
            c.instanceFollowRedirects = false
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            try {
                when (val code = c.responseCode) {
                    401 -> Probe.KEY
                    in 200..299 -> {
                        val head = c.inputStream.use { s ->
                            val buf = ByteArray(4096)
                            var n = 0
                            while (n < buf.size) {
                                val r = s.read(buf, n, buf.size - n)
                                if (r < 0) break
                                n += r
                            }
                            String(buf, 0, n, Charsets.UTF_8)
                        }
                        // 드림랜드 index.html 의 제목. 같은 포트에 실리태번 등이 있으면 걸러낸다
                        if (head.contains("Dreamland", ignoreCase = true)) Probe.UP else Probe.OTHER
                    }
                    in 300..399 -> Probe.UP
                    else -> if (code == 403 || code == 503) Probe.UP else Probe.OTHER
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Probe.DOWN
        }
    }

    /** auto = 앱을 처음 켰을 때: 깨어 있으면 바로 들어간다 */
    private fun checkServer(auto: Boolean) {
        if (waking || sleeping) return
        pushState("checking")
        val url = serverUrl
        io.execute {
            val r = probe(url)
            main.post {
                if (waking) return@post
                when (r) {
                    Probe.UP -> if (auto) enterDream() else pushState("awake")
                    Probe.KEY -> pushState("key")
                    Probe.OTHER -> pushState("other")
                    Probe.DOWN -> pushState("asleep")
                }
            }
        }
    }

    /** "꿈속으로"를 눌렀을 때 */
    private fun enter() {
        if (waking || sleeping) return
        pushState("checking")
        val url = serverUrl
        io.execute {
            val r = probe(url)
            main.post {
                when (r) {
                    Probe.UP -> enterDream()
                    Probe.KEY -> pushState("key")
                    Probe.OTHER -> pushState("other")
                    Probe.DOWN -> if (isLocal) wakeTermux() else pushState("asleep")
                }
            }
        }
    }

    // ── 터묵스 ──

    private fun termuxInstalled(): Boolean = try {
        packageManager.getPackageInfo(TERMUX_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun hasTermuxPermission() =
        ContextCompat.checkSelfPermission(this, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 셸에 넣을 문자열: 작은따옴표로 감싸고 안의 ' 는 '\'' 로 */
    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun folderExpr(f: String): String = when {
        f == "~" -> "\"\$HOME\""
        f.startsWith("~/") -> "\"\$HOME\"/" + sq(f.removePrefix("~/"))
        else -> sq(f)
    }

    private fun wakeScript(): String {
        val f = folder.trim().ifEmpty { DEFAULT_FOLDER }
        return buildString {
            appendLine("cd ${folderExpr(f)} 2>/dev/null || { echo; echo \"드림랜드 폴더를 찾지 못했어요: \"${sq(f)}; echo '앱의 설정 > 드림랜드 폴더 를 확인해 주세요.'; echo; echo '엔터를 누르면 창이 닫혀요.'; read -r _; exit 1; }")
            if (wakeLock) appendLine("command -v termux-wake-lock >/dev/null && termux-wake-lock")
            appendLine("echo; echo '  Dreamland 로 들어가는 중… (포트 $port)'")
            appendLine("PORT=$port node server.js")
            // 앱의 "잠재우기"(SIGTERM → 143)로 꺼졌으면 창을 바로 닫는다. 그 밖의 이유면 메시지를 보여 주고 기다린다
            appendLine("[ \$? -eq 143 ] && exit 0")
            appendLine("echo; echo '드림랜드가 꺼졌어요. 엔터를 누르면 창이 닫혀요.'; read -r _")
        }
    }

    /**
     * 드림랜드 폴더에서 이 포트로 돌고 있는 node server.js 만 골라 끈다.
     * (실리태번도 node server.js 로 돌기 때문에 이름만 보고 끄면 안 된다)
     * 작업 폴더(/proc/<pid>/cwd)와 PORT 환경 변수(없으면 기본 4173)를 함께 본다.
     */
    private fun sleepScript(): String {
        val f = folder.trim().ifEmpty { DEFAULT_FOLDER }
        return buildString {
            appendLine("dir=\$(cd ${folderExpr(f)} 2>/dev/null && pwd -P) || exit 1")
            appendLine("for d in /proc/[0-9]*; do")
            // 실행 파일이 node 이고 첫 인자가 server.js 인 것만 (bash 창 같은 다른 프로세스는 빼고)
            appendLine("  set -- \$(tr '\\0' '\\n' < \"\$d/cmdline\" 2>/dev/null | head -n 2)")
            appendLine("  case \"\$1\" in *node) ;; *) continue ;; esac")
            appendLine("  case \"\$2\" in *server.js) ;; *) continue ;; esac")
            appendLine("  [ \"\$(readlink \"\$d/cwd\")\" = \"\$dir\" ] || continue")
            appendLine("  p=\$(tr '\\0' '\\n' < \"\$d/environ\" 2>/dev/null | sed -n 's/^PORT=//p')")
            appendLine("  [ \"\${p:-$DEFAULT_PORT}\" = '$port' ] && kill \"\${d#/proc/}\"")
            appendLine("done")
            if (wakeLock) appendLine("command -v termux-wake-unlock >/dev/null && termux-wake-unlock")
            appendLine("exit 0")
        }
    }

    // ── 터묵스의 답 받기 ──
    // 뒤에서(background) 돈 명령의 출력과 끝난 번호를 터묵스가 PendingIntent 로 돌려준다.
    // 받는 곳은 이 앱 안에만 열려 있다 (PendingIntent 는 이 앱 이름으로 보내지므로 밖에서는 못 보냄)

    private val resultAction by lazy { "$packageName.TERMUX_RESULT" }
    private var nextResultId = 1
    private val resultCallbacks = HashMap<Int, (Int, String, String) -> Unit>()

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cb = resultCallbacks.remove(i.getIntExtra("dl_id", -1)) ?: return
            val b = i.getBundleExtra("result")
            val err = b?.getInt("err", -1) ?: -1
            // err: -1 이면 터묵스가 명령을 제대로 돌렸다. 그 밖이면 터묵스가 실행을 거절한 것 (외부 앱 허용 안 됨 등)
            if (b == null || err != -1) cb(-100, "", b?.getString("errmsg") ?: "")
            else cb(b.getInt("exitCode", -1), b.getString("stdout") ?: "", b.getString("stderr") ?: "")
        }
    }

    /** 뒤에서 명령을 돌리고, 끝나면 onDone(끝 번호, 출력, 오류 출력). 터묵스가 답이 없으면 timeoutMs 뒤에 -101 */
    private fun runForResult(script: String, timeoutMs: Long, onDone: (Int, String, String) -> Unit): String? {
        val id = nextResultId++
        resultCallbacks[id] = onDone
        val err = runInTermux(script, background = true, resultId = id)
        if (err != null) { resultCallbacks.remove(id); return err }
        main.postDelayed({ resultCallbacks.remove(id)?.invoke(-101, "", "") }, timeoutMs)
        return null
    }

    /** 터묵스에 명령 보내기. background = true 면 창(세션) 없이 조용히 돈다 */
    private fun runInTermux(script: String, background: Boolean, resultId: Int? = null): String? {
        if (!termuxInstalled()) return "termux-missing"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            // 0: 새 세션(창)으로 바꾸고 터묵스 화면을 연다.
            // (2 "화면은 열지 않음" 은 터묵스 0.119 에서 명령이 아예 실행되지 않아서, 조용히 켤 때는 background 로 보낸다)
            if (!background) putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Dreamland")
            if (resultId != null) {
                val back = Intent(resultAction).setPackage(packageName).putExtra("dl_id", resultId)
                // 터묵스가 결과를 채워 넣어야 해서 MUTABLE (받는 곳을 이 앱으로 못 박아 두었다)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                    PendingIntent.getBroadcast(this@MainActivity, resultId, back, flags))
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            null
        } catch (e: SecurityException) {
            "permission"
        } catch (e: Exception) {
            "launch"
        }
    }

    private fun wakeTermux() {
        if (!termuxInstalled()) return pushState("error", "code" to "termux-missing")
        if (!hasTermuxPermission()) {
            wakeAfterPermission = true
            permissionAsk.launch(TERMUX_PERMISSION)
            return
        }
        // 터묵스 화면으로 이동: 터묵스 창에서 켜고 화면을 연다 / 끔: 창 없이 뒤에서 켠다 (터묵스 알림에 "task" 로 보임)
        runInTermux(wakeScript(), background = !showTermux)?.let { return pushState("error", "code" to it) }
        if (showTermux) showTermuxScreen()
        startWaiting(install = false)
    }

    /**
     * 터묵스 화면을 앞으로 연다.
     * 터묵스는 창(세션)을 만들어도 그 창이 한 번 화면에 그려지기 전까지 명령을 시작하지 않는다.
     * Android 14 는 뒤에 있는 터묵스가 스스로 화면을 여는 걸 막으므로, 앞에 있는 이 앱이 대신 연다.
     */
    private fun showTermuxScreen() {
        val i = packageManager.getLaunchIntentForPackage(TERMUX_PKG) ?: return
        main.postDelayed({ try { startActivity(i) } catch (e: Exception) { } }, 300)
    }

    /** 켜지기를 기다리기 시작한다. install = 처음 설치(내려받기 + 켜기)라 더 오래 기다린다 */
    private fun startWaiting(install: Boolean) {
        waking = true
        installing = install
        wakeStartedAt = SystemClock.elapsedRealtime()
        pushState(if (install) "installing" else "waking", "secs" to 0)
        main.postDelayed(::pollWake, POLL_MS)
    }

    private fun pollWake() {
        if (!waking) return
        val url = serverUrl
        io.execute {
            val r = probe(url)
            main.post {
                if (!waking) return@post
                val elapsed = SystemClock.elapsedRealtime() - wakeStartedAt
                val state = if (installing) "installing" else "waking"
                when {
                    r == Probe.UP -> {
                        waking = false
                        if (installing) { installing = false; onboarded = true }
                        enterDream()
                    }
                    r == Probe.KEY -> { waking = false; installing = false; pushState("key") }
                    elapsed > (if (installing) INSTALL_TIMEOUT_MS else WAKE_TIMEOUT_MS) -> {
                        waking = false
                        pushState("error", "code" to if (installing) "install-timeout" else "timeout")
                        installing = false
                    }
                    else -> {
                        val slow = elapsed > (if (installing) INSTALL_SLOW_MS else SLOW_MS)
                        pushState(state, "secs" to (elapsed / 1000).toInt(), "slow" to slow)
                        main.postDelayed(::pollWake, POLL_MS)
                    }
                }
            }
        }
    }

    // ── 처음 설치 (드림랜드 README 의 Termux 순서) ──

    /**
     * pkg upgrade → pkg install git nodejs-lts → git clone (이미 있으면 건너뜀) → 바로 켜기.
     * 진행 상황이 보이도록 언제나 터묵스 창에서 실행한다.
     *
     * 드림랜드 README 는 `pkg update` 라고 하지만, 그건 목록만 새로 고친다.
     * 새로 깐 터묵스는 기본 라이브러리(libc++ 등)가 오래돼서 새로 받은 node 가
     * `CANNOT LINK EXECUTABLE "node"` 로 실행되지 않으므로 업그레이드까지 한다 (터묵스 공식 안내도 pkg upgrade).
     */
    private fun installScript(): String {
        val f = folder.trim().ifEmpty { DEFAULT_FOLDER }
        val dir = folderExpr(f)
        return buildString {
            // 배터리 최적화 해제 창이 맨 처음에 뜨게 (끝에 뜨면 내려받는 동안 터묵스가 느려질 수 있다)
            if (wakeLock) appendLine("command -v termux-wake-lock >/dev/null && termux-wake-lock")
            appendLine("echo; echo '  Dreamland 를 들이는 중… 처음에는 몇 분 걸려요.'; echo")
            appendLine("yes | pkg upgrade -y")
            appendLine("pkg install -y git nodejs-lts || { echo; echo '프로그램을 설치하지 못했어요. 인터넷 연결을 확인하고 앱에서 다시 눌러 주세요.'; echo '엔터를 누르면 창이 닫혀요.'; read -r _; exit 1; }")
            appendLine("if [ -d $dir/.git ]; then echo '드림랜드가 이미 있어요. 그대로 켤게요.'; else git clone $DREAMLAND_REPO $dir || { echo; echo '드림랜드를 내려받지 못했어요. 인터넷 연결을 확인하고 앱에서 다시 눌러 주세요.'; echo '엔터를 누르면 창이 닫혀요.'; read -r _; exit 1; }; fi")
            // 드림랜드가 포트를 열면 이 앱을 다시 앞으로 불러온다 (지금 앞에 있는 터묵스가 부르는 건 막히지 않는다)
            appendLine("( for i in \$(seq 1 90); do sleep 1; if (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null; then am start -n $packageName/.MainActivity >/dev/null 2>&1; break; fi; done ) &")
            append(wakeScript())
        }
    }

    private fun installDreamland() {
        if (waking || sleeping) return
        if (!termuxInstalled()) return pushState("error", "code" to "termux-missing")
        if (!hasTermuxPermission()) {
            installAfterPermission = true
            permissionAsk.launch(TERMUX_PERMISSION)
            return
        }
        val url = serverUrl
        pushState("checking")
        io.execute {
            val r = probe(url)
            main.post {
                // 이미 켜져 있으면 설치할 것 없이 바로 들어간다
                if (r == Probe.UP) { onboarded = true; enterDream(); return@post }
                runInTermux(installScript(), background = false)?.let { return@post pushState("error", "code" to it) }
                showTermuxScreen()   // 화면이 떠야 설치가 시작된다
                startWaiting(install = true)
            }
        }
    }

    // ── 잠재우기 (드림랜드 끄기) ──

    private fun sleepDream() {
        if (waking || sleeping || !isLocal) return
        if (!hasTermuxPermission()) return pushState("error", "code" to "permission")
        runInTermux(sleepScript(), background = true)?.let { return pushState("error", "code" to it) }
        sleeping = true
        sleepStartedAt = SystemClock.elapsedRealtime()
        dreamLoadedUrl = null           // 다음에 들어갈 때는 새로 불러온다
        dreamView.loadUrl("about:blank")
        pushState("sleeping")
        main.postDelayed(::pollSleep, POLL_MS)
    }

    private fun pollSleep() {
        if (!sleeping) return
        val url = serverUrl
        io.execute {
            val r = probe(url)
            main.post {
                if (!sleeping) return@post
                val elapsed = SystemClock.elapsedRealtime() - sleepStartedAt
                when {
                    r == Probe.DOWN && wakeAfterSleep -> {
                        // 업데이트 뒤: 새 버전으로 다시 켠다
                        sleeping = false; wakeAfterSleep = false
                        wakeTermux()
                    }
                    r == Probe.DOWN -> { sleeping = false; pushState("asleep", "slept" to true) }
                    elapsed > SLEEP_TIMEOUT_MS -> { sleeping = false; wakeAfterSleep = false; pushState("error", "code" to "stop") }
                    else -> main.postDelayed(::pollSleep, POLL_MS)
                }
            }
        }
    }

    // ── 업데이트 (드림랜드 README: git pull) ──
    // 드림랜드는 만든 결과(dist)까지 저장소에 들어 있어서 git pull 만 하면 된다.
    // 켜져 있던 서버는 옛 파일을 들고 있으므로, 받은 뒤 한 번 껐다가 다시 켠다.

    private var updating = false
    private var wakeAfterSleep = false

    private fun gitPrelude(): String {
        val f = folder.trim().ifEmpty { DEFAULT_FOLDER }
        return buildString {
            appendLine("export GIT_TERMINAL_PROMPT=0")
            appendLine("cd ${folderExpr(f)} 2>/dev/null || exit 3")
            appendLine("command -v git >/dev/null || exit 4")
            appendLine("[ -d .git ] || exit 5")
        }
    }

    private fun updateError(code: Int, stderr: String): String = when (code) {
        3 -> "드림랜드 폴더를 찾지 못했어요. 설정의 드림랜드 폴더를 확인해 주세요."
        4 -> "터묵스에 git 이 없어요. 터묵스에서 pkg install git 을 해 주세요."
        5 -> "git clone 으로 받은 폴더가 아니라서 자동 업데이트를 할 수 없어요. 새 ZIP 을 받아 data 폴더만 남기고 덮어써 주세요."
        6 -> "업데이트를 확인하지 못했어요. 인터넷 연결을 확인해 주세요."
        7 -> "업데이트를 받지 못했어요. 드림랜드 폴더의 파일을 직접 고쳤다면 그 때문일 수 있어요." +
            (stderr.lineSequence().firstOrNull { it.isNotBlank() }?.let { "\n($it)" } ?: "")
        -100 -> "터묵스가 부탁을 거절했어요. 설정의 '외부 앱 허용' 을 확인해 주세요."
        -101 -> "터묵스가 답하지 않았어요. 잠시 뒤 다시 해 주세요."
        else -> "알 수 없는 문제가 생겼어요. (코드 $code)"
    }

    private fun pushUpdate(phase: String, vararg extra: Pair<String, Any>) {
        val o = JSONObject().put("phase", phase)
        extra.forEach { (k, v) -> o.put(k, v) }
        js("dl.onUpdate && dl.onUpdate($o)")
    }

    private fun updateBlocked(): Boolean {
        if (!isLocal) { pushUpdate("error", "msg" to "다른 기기의 드림랜드는 그 기기에서 업데이트해 주세요."); return true }
        if (!termuxInstalled()) { pushUpdate("error", "msg" to "터묵스가 설치돼 있지 않아요."); return true }
        if (!hasTermuxPermission()) { pushUpdate("error", "msg" to "터묵스 실행 권한이 필요해요."); return true }
        return updating || waking || sleeping
    }

    /** 새 버전이 있는지만 본다 (git fetch). 받지는 않는다 */
    private fun checkUpdate() {
        if (updateBlocked()) return
        updating = true
        pushUpdate("checking")
        val script = gitPrelude() + """
            git fetch -q || exit 6
            git rev-list --count HEAD..@{u} 2>/dev/null || echo 0
            git log -1 --format=%cs @{u} 2>/dev/null || echo
            git log -1 --format=%cs HEAD
        """.trimIndent()
        runForResult(script, 90_000) { code, out, err ->
            updating = false
            if (code != 0) return@runForResult pushUpdate("error", "msg" to updateError(code, err))
            val lines = out.lines().map { it.trim() }
            val count = lines.getOrNull(0)?.toIntOrNull() ?: 0
            pushUpdate(if (count > 0) "available" else "latest",
                "count" to count, "remote" to (lines.getOrNull(1) ?: ""), "local" to (lines.getOrNull(2) ?: ""))
        }?.let { updating = false; pushUpdate("error", "msg" to updateError(-100, "")) }
    }

    /** git pull 을 하고, 켜져 있었으면 껐다가 다시 켠다 */
    private fun applyUpdate() {
        if (updateBlocked()) return
        updating = true
        pushUpdate("pulling")
        val url = serverUrl
        val script = gitPrelude() + """
            git pull --ff-only -q || exit 7
            git log -1 --format=%cs HEAD
        """.trimIndent()
        runForResult(script, 180_000) { code, out, err ->
            updating = false
            if (code != 0) return@runForResult pushUpdate("error", "msg" to updateError(code, err))
            val date = out.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            io.execute {
                val up = probe(url) == Probe.UP
                main.post {
                    dreamLoadedUrl = null   // 다음에 들어갈 때 새 화면을 불러온다
                    if (up) {
                        pushUpdate("restarting", "local" to date)
                        wakeAfterSleep = true
                        sleepDream()
                    } else {
                        pushUpdate("done", "local" to date)
                    }
                }
            }
        }?.let { updating = false; pushUpdate("error", "msg" to updateError(-100, "")) }
    }

    // ── 접속 키 (다른 기기에서 --lan 으로 켠 드림랜드) ──

    private fun submitKey(key: String) {
        val url = serverUrl
        io.execute {
            val result: Pair<Boolean, String> = try {
                val c = URL("$url/?key=" + URLEncoder.encode(key, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 2000
                c.readTimeout = 4000
                c.instanceFollowRedirects = false
                try {
                    when (c.responseCode) {
                        302 -> {
                            // 서버가 주는 dl_key 쿠키를 웹뷰에도 넣어 두면 다음부터는 키를 묻지 않는다
                            c.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
                            CookieManager.getInstance().flush()
                            true to ""
                        }
                        429 -> false to "여러 번 틀려서 잠겼어요. 10분 뒤에 다시 해 주세요."
                        else -> false to "키가 맞지 않아요."
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                false to "드림랜드에 닿지 않아요."
            }
            main.post {
                js("dl.onKeyResult(" + JSONObject().put("ok", result.first).put("msg", result.second) + ")")
                if (result.first) {
                    dreamLoadedUrl = null
                    enterDream()
                }
            }
        }
    }

    // ── 첫 화면과 주고받기 ──

    private fun configJson() = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("folder", folder)
        put("showTermux", showTermux)
        put("wakeLock", wakeLock)
        put("local", isLocal)
        put("installed", termuxInstalled())
        put("permission", hasTermuxPermission())
        put("setup", SETUP_COMMAND)
        put("onboarded", onboarded)
        put("setupDone", setupDone)
        put("repo", DREAMLAND_REPO)
        // 터묵스 GitHub 판은 CPU 종류별로 파일이 따로라, 이 폰에 맞는 이름을 알려 주려고 넘긴다
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
    }

    private fun pushState(state: String, vararg extra: Pair<String, Any>) {
        val o = JSONObject().put("state", state).put("local", isLocal).put("cfg", configJson())
        extra.forEach { (k, v) -> o.put(k, v) }
        lastState = o
        js("dl.onState($o)")
    }

    private fun js(code: String) {
        if (!startReady) return
        startView.evaluateJavascript("window.dl && $code", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getState(): String = configJson().toString()

        @JavascriptInterface
        fun ready() = main.post {
            startReady = true
            insetsJs?.let { js(it) } ?: ViewCompat.requestApplyInsets(startView)
            if (openSettingsWhenReady) {
                openSettingsWhenReady = false
                js("dl.openSettings()")
            }
            lastState?.let { js("dl.onState($it)") }
            // 처음 질문에 아직 답하지 않았으면 자동으로 들어가지 않는다 (질문 화면을 먼저 보여 준다)
            val auto = firstCheck && onboarded
            firstCheck = false
            checkServer(auto)
        }.let { }

        @JavascriptInterface
        fun enter() = main.post { this@MainActivity.enter() }.let { }

        // 깨우는 중에는 기다리기를 계속하고, 아니면 다시 살펴본다
        @JavascriptInterface
        fun refresh() = main.post { if (!waking && !sleeping) checkServer(auto = false) }.let { }

        @JavascriptInterface
        fun saveSettings(json: String) = main.post {
            val o = JSONObject(json)
            val before = serverUrl
            showTermux = o.optBoolean("showTermux", true)
            wakeLock = o.optBoolean("wakeLock", true)
            folder = o.optString("folder", DEFAULT_FOLDER).trim().ifEmpty { DEFAULT_FOLDER }
            port = o.optInt("port", DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT
            host = o.optString("host", DEFAULT_HOST).trim().ifEmpty { DEFAULT_HOST }
            // 주소나 포트가 바뀌었을 때만 다시 살펴본다 (스위치만 바꿀 때는 화면이 깜빡이지 않게)
            if (serverUrl != before) {
                dreamLoadedUrl = null
                if (!waking && !sleeping) checkServer(auto = false)
            }
        }.let { }

        @JavascriptInterface
        fun requestPermission() = main.post {
            if (hasTermuxPermission()) js("dl.onPermission(true)")
            else permissionAsk.launch(TERMUX_PERMISSION)
        }.let { }

        @JavascriptInterface
        fun copySetup() = main.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("termux setup", SETUP_COMMAND))
        }.let { }

        @JavascriptInterface
        fun openTermux(): Boolean {
            val i = packageManager.getLaunchIntentForPackage(TERMUX_PKG) ?: return false
            main.post { startActivity(i) }
            return true
        }

        @JavascriptInterface
        fun sleep() = main.post { sleepDream() }.let { }

        @JavascriptInterface
        fun submitKey(key: String) = main.post { this@MainActivity.submitKey(key.trim()) }.let { }

        /** 처음 질문의 답: true = 이미 쓰고 있음(또는 안내를 마침) / false = 안내 다시 보기 */
        @JavascriptInterface
        fun setOnboarded(v: Boolean) = main.post { onboarded = v }.let { }

        @JavascriptInterface
        fun install() = main.post { installDreamland() }.let { }

        @JavascriptInterface
        fun checkUpdate() = main.post { this@MainActivity.checkUpdate() }.let { }

        @JavascriptInterface
        fun applyUpdate() = main.post { this@MainActivity.applyUpdate() }.let { }

        /** 설치 안내 02 "했어요" 표시 (첫 화면은 웹 저장소를 쓰지 않아서 앱 쪽에 저장한다) */
        @JavascriptInterface
        fun setSetupDone(v: Boolean) = main.post { setupDone = v }.let { }

        /** 설치 안내의 링크 (정해 둔 주소만 브라우저로 연다) */
        @JavascriptInterface
        fun openUrl(url: String): Boolean {
            if (url !in GUIDE_URLS) return false
            main.post {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
            }
            return true
        }
    }
}

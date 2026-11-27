package com.dreamland.webview

import android.annotation.SuppressLint
import android.content.ClipData
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

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            fileCallback?.onReceiveValue(uris.toTypedArray())
            fileCallback = null
        }

    // ── 화면 ──

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateOldUrl()
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
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    filePicker.launch("*/*")
                    return true
                }
            }
        }

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

        val root = FrameLayout(this)
        root.addView(dreamView, FrameLayout.LayoutParams(-1, -1))
        root.addView(startView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
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
                    dreamView.visibility == View.VISIBLE && dreamView.canGoBack() -> dreamView.goBack()
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
        if (dreamLoadedUrl != url) {
            dreamView.loadUrl(
                if (key != null) "$url/?key=" + URLEncoder.encode(key, "UTF-8") else url
            )
            dreamView.clearHistory()
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

    /** 터묵스에 명령 보내기. background = true 면 창(세션) 없이 조용히 돈다 */
    private fun runInTermux(script: String, background: Boolean): String? {
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
                    r == Probe.DOWN -> { sleeping = false; pushState("asleep", "slept" to true) }
                    elapsed > SLEEP_TIMEOUT_MS -> { sleeping = false; pushState("error", "code" to "stop") }
                    else -> main.postDelayed(::pollSleep, POLL_MS)
                }
            }
        }
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

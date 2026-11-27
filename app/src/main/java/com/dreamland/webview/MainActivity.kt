package com.dreamland.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        // 처음 설치했을 때 쓰는 기본 주소. 드림랜드 server.js 의 기본 포트가 4173 이다.
        // (8000 은 실리태번 기본 포트라 겹치면 실리태번이 열린다)
        const val DEFAULT_URL = "http://127.0.0.1:4173"

        // 1.0.1 빌드의 잘못된 기본값. 이 값이 저장돼 있으면 새 기본값으로 옮긴다.
        private const val OLD_DEFAULT_URL = "http://127.0.0.1:8000"

        // 앱 아이콘을 길게 눌러 "서버 주소 변경"을 고르면 오는 신호
        const val ACTION_SETTINGS = "com.dreamland.webview.SETTINGS"

        // 오류 화면의 "주소 변경" 버튼이 여는 앱 내부 주소
        private const val SETTINGS_LINK = "dreamland://settings"

        private const val PREFS = "dreamland"
        private const val KEY_URL = "server_url"
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // 사람마다 다른 서버 주소를 각자의 폰에 저장해 둔다
    private var serverUrl: String
        get() {
            val saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
            return if (saved == OLD_DEFAULT_URL) DEFAULT_URL else saved
        }
        set(value) = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, value).apply()

    // 파일 업로드(캐릭터 카드, 이미지 등)
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            fileCallback?.onReceiveValue(uris.toTypedArray())
            fileCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                if (request.url.toString() == SETTINGS_LINK) {
                    showServerDialog()
                    return true
                }
                return false
            }

            // 서버가 아직 안 켜졌을 때 안내 + 다시 시도 / 주소 변경 버튼
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) showErrorPage()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                filePicker.launch("*/*")
                return true
            }
        }

        // 뒤로가기: 이전 페이지 → 없으면 앱 종료
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(serverUrl)

        if (intent?.action == ACTION_SETTINGS) showServerDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SETTINGS) showServerDialog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun showErrorPage() {
        val url = escapeHtml(serverUrl)
        webView.loadDataWithBaseURL(
            null,
            """
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <body style="background:#111;color:#eee;font-family:sans-serif;
                         display:flex;flex-direction:column;align-items:center;
                         justify-content:center;height:100vh;margin:0;padding:0 24px;
                         box-sizing:border-box;text-align:center">
              <p>서버에 연결할 수 없어요.<br>터묵스에서 드림랜드를 먼저 켜 주세요.<br>(sh start.sh)</p>
              <p style="color:#999;font-size:14px;word-break:break-all">지금 주소: $url</p>
              <button onclick="location.href='$url'"
                      style="padding:12px 24px;font-size:16px;margin:8px">다시 시도</button>
              <a href="$SETTINGS_LINK"
                 style="color:#8ab4f8;font-size:16px;margin:8px">주소 변경</a>
            </body>
            """.trimIndent(),
            "text/html", "utf-8", null
        )
    }

    private fun showServerDialog() {
        val input = EditText(this).apply {
            setText(serverUrl)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = DEFAULT_URL
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("서버 주소")
            .setMessage("터묵스에서 드림랜드를 켜면 나오는 주소를 넣어 주세요.")
            .setView(container)
            .setPositiveButton("저장") { _, _ -> applyUrl(input.text.toString()) }
            .setNeutralButton("기본값") { _, _ -> applyUrl(DEFAULT_URL) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun applyUrl(raw: String) {
        var url = raw.trim()
        if (url.isEmpty()) url = DEFAULT_URL
        if (!url.contains("://")) url = "http://$url"
        serverUrl = url
        webView.clearHistory()
        webView.loadUrl(url)
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}

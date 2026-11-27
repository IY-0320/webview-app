package com.dreamland.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // ▼ 드림랜드 실행 시 터묵스에 뜨는 주소로 바꿔 주세요
    private val startUrl = "http://127.0.0.1:8000"

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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
            // 서버가 아직 안 켜졌을 때 안내 + 다시 시도 버튼
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(
                        null,
                        """
                        <body style="background:#111;color:#eee;font-family:sans-serif;
                                     display:flex;flex-direction:column;align-items:center;
                                     justify-content:center;height:100vh;margin:0">
                          <p>서버에 연결할 수 없어요.<br>터묵스에서 드림랜드를 먼저 켜 주세요.</p>
                          <button onclick="location.href='$startUrl'"
                                  style="padding:12px 24px;font-size:16px">다시 시도</button>
                        </body>
                        """.trimIndent(),
                        "text/html", "utf-8", null
                    )
                }
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
        else webView.loadUrl(startUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}

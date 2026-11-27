package com.dreamland.webview

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 드림랜드의 "내보내기 · 백업 받기" 를 폰에 저장한다.
 *
 * 드림랜드는 파일을 `<a download>` 를 눌러서 내려준다 (blob: 주소, 또는 서버의 backup-file 주소).
 * 웹뷰는 이걸 스스로 저장하지 못하므로, 드림랜드 화면에 작은 스크립트를 넣어 그 클릭을 가로채고
 * 파일 내용을 "DreamSave" 통로로 앱에 넘긴다. 앱은 받은 파일을 다운로드/Dreamland 폴더에 넣는다.
 *
 * 통로는 저장만 할 수 있고(터묵스 같은 다른 기능은 없음), 설정한 드림랜드 주소에서 온 것만 받는다.
 */
class Saver(
    private val act: AppCompatActivity,
    private val view: WebView,
    private val serverUrl: () -> String,
) {
    companion object {
        private const val CHUNK = 786_432   // 3 의 배수: 조각마다 base64 를 따로 풀어도 이어 붙인 것과 같다
        val FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Dreamland"

        private val SCRIPT = """(function(){
  if (window.__dreamSave || !window.DreamSave) return; window.__dreamSave = 1;
  var CH = $CHUNK, n = 0;
  // 드림랜드의 보안 정책(connect-src *)은 blob: 주소 읽기를 막는다. 그래서 주소를 다시 읽지 않고,
  // 드림랜드가 blob: 주소를 만들 때 그 Blob 을 기억해 두었다가 그대로 읽는다 (지운 뒤에도 1분은 남겨 둔다)
  var blobs = new Map(), mk = URL.createObjectURL, rm = URL.revokeObjectURL;
  URL.createObjectURL = function(o){ var u = mk.apply(URL, arguments); if (o instanceof Blob) blobs.set(u, o); return u; };
  URL.revokeObjectURL = function(u){ setTimeout(function(){ blobs.delete(u); }, 60000); return rm.apply(URL, arguments); };
  function fromData(href){   // data:종류;base64,....
    var i = href.indexOf(','), head = href.slice(5, i), body = href.slice(i + 1);
    var s = /;base64/i.test(head) ? atob(body) : unescape(encodeURIComponent(decodeURIComponent(body)));
    var u = new Uint8Array(s.length); for (var k = 0; k < s.length; k++) u[k] = s.charCodeAt(k);
    return new Blob([u], {type: head.split(';')[0]});
  }
  function post(o){ try { DreamSave.postMessage(JSON.stringify(o)); } catch (e) {} }
  function save(a){
    var href = a.href, name = a.getAttribute('download') || '';
    if (/^https?:/i.test(href)) { post({t:'url', url:href, name:name}); return; }
    var id = 's' + (++n) + '-' + Date.now();
    new Promise(function(ok, no){
      if (blobs.has(href)) return ok(blobs.get(href));
      if (/^data:/i.test(href)) return ok(fromData(href));
      no();
    }).then(function(b){
      post({t:'begin', id:id, name:name, mime:b.type || '', size:b.size});
      var off = 0;
      (function next(){
        if (off >= b.size) { post({t:'end', id:id}); return; }
        var fr = new FileReader();
        fr.onload = function(){ var s = String(fr.result); post({t:'chunk', id:id, b64:s.slice(s.indexOf(',') + 1)}); off += CH; next(); };
        fr.onerror = function(){ post({t:'fail', id:id}); };
        fr.readAsDataURL(b.slice(off, off + CH));
      })();
    }).catch(function(){ post({t:'fail', id:id}); });
  }
  document.addEventListener('click', function(e){
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!a || !a.href) return;
    e.preventDefault();
    save(a);
  }, true);
})();"""
    }

    private class Part(val file: File, val out: FileOutputStream, val name: String, val mime: String) {
        var bytes = 0L
    }

    // 파일 쓰기는 이 한 줄(스레드)에서 차례대로 한다. parts 도 여기서만 만진다
    private val io = Executors.newSingleThreadExecutor()
    private val parts = HashMap<String, Part>()
    private val dir by lazy { File(act.cacheDir, "saves").apply { mkdirs() } }

    // Android 9 이하는 다운로드 폴더에 바로 넣으려면 저장소 권한이 필요해서, 저장할 곳을 사람에게 고르게 한다
    private var pickFile: File? = null
    private val picker = act.registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pickFile ?: return@registerForActivityResult
        pickFile = null
        io.execute {
            val ok = uri != null && runCatching {
                act.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            }.isSuccess
            f.delete()
            if (uri != null) toast(if (ok) "저장했어요" else "저장하지 못했어요")
        }
    }

    /** onCreate 에서 한 번. 통로를 지원하지 않는 아주 오래된 웹뷰면 아무것도 하지 않는다 */
    fun attach() {
        // 저장하다 앱이 꺼져 남은 임시 파일을 지운다
        io.execute { dir.listFiles()?.forEach { it.delete() } }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, "DreamSave", setOf("*")) { _, msg, origin, isMain, _ ->
            if (!isMain || !sameOrigin(origin, Uri.parse(serverUrl()))) return@addWebMessageListener
            val o = runCatching { JSONObject(msg.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            onMessage(o)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
        else injectOnLoad = true
        // <a download> 가 아닌 방법으로 파일 주소를 연 경우 (서버가 "첨부 파일" 로 보낼 때)
        view.setDownloadListener { url, _, disposition, mime, _ ->
            if (url.startsWith("http")) fetch(url, URLUtil.guessFileName(url, disposition, mime))
        }
    }

    /** DOCUMENT_START_SCRIPT 가 없는 웹뷰: 페이지를 다 불러온 뒤 넣는다 (onPageFinished 에서 부른다) */
    var injectOnLoad = false
        private set

    fun inject() {
        if (injectOnLoad) view.evaluateJavascript(SCRIPT, null)
    }

    private fun sameOrigin(a: Uri, b: Uri): Boolean {
        fun port(u: Uri) = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }

    private fun onMessage(o: JSONObject) {
        val id = o.optString("id")
        when (o.optString("t")) {
            "url" -> {
                val url = o.optString("url")
                if (sameOrigin(Uri.parse(url), Uri.parse(serverUrl()))) fetch(url, o.optString("name"))
                // 다른 사이트의 파일은 크롬처럼 브라우저가 받게 넘긴다
                else act.runOnUiThread {
                    try { act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
                }
            }
            "begin" -> {
                val name = cleanName(o.optString("name"))
                val mime = o.optString("mime")
                io.execute {
                    runCatching {
                        val f = File.createTempFile("save", ".part", dir)
                        parts[id] = Part(f, FileOutputStream(f), name, mime)
                    }
                }
            }
            "chunk" -> {
                val b64 = o.optString("b64")
                io.execute {
                    val p = parts[id] ?: return@execute
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        p.bytes += bytes.size
                        p.out.write(bytes)
                    }.onFailure { drop(id) }
                }
            }
            "end" -> io.execute {
                val p = parts.remove(id) ?: return@execute
                runCatching { p.out.close() }
                publish(p.file, p.name, p.mime)
            }
            "fail" -> io.execute { drop(id); toast("파일을 저장하지 못했어요") }
        }
    }

    private fun drop(id: String) {
        val p = parts.remove(id) ?: return
        runCatching { p.out.close() }
        p.file.delete()
    }

    /** 드림랜드 서버의 파일(백업 등)을 받아 저장한다. 웹뷰의 쿠키(접속 키)를 그대로 쓴다 */
    private fun fetch(url: String, suggested: String) {
        toast("받는 중…")
        io.execute {
            val f = File.createTempFile("save", ".part", dir)
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 30_000
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                try {
                    if (c.responseCode !in 200..299) throw Exception("http ${c.responseCode}")
                    c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                    val name = suggested.ifBlank {
                        URLUtil.guessFileName(url, c.getHeaderField("Content-Disposition"), c.contentType)
                    }
                    publish(f, cleanName(name), c.contentType?.substringBefore(';') ?: "")
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                f.delete()
                toast("파일을 받지 못했어요")
            }
        }
    }

    /** 다 받은 임시 파일을 다운로드/Dreamland 에 넣는다 (io 스레드에서) */
    private fun publish(src: File, name: String, blobMime: String) {
        val mime = mimeFor(name, blobMime)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            act.runOnUiThread { pickFile = src; picker.launch(name) }
            return
        }
        val r = act.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        try {
            uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("insert")
            r.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, values, null, null)
            toast("다운로드/Dreamland 에 저장했어요\n$name")
        } catch (e: Exception) {
            uri?.let { runCatching { r.delete(it, null, null) } }
            toast("파일을 저장하지 못했어요")
        } finally {
            src.delete()
        }
    }

    /** 이름의 확장자를 먼저 보고(MediaStore 는 종류와 확장자가 어긋나면 이름 뒤에 확장자를 더 붙인다), 없으면 드림랜드가 준 종류 */
    private fun mimeFor(name: String, given: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: given.takeIf { it.contains('/') }
            ?: "application/octet-stream"
    }

    private fun cleanName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().trimStart('.').take(150)
            .ifEmpty { "dreamland-file" }

    private fun toast(t: String) = act.runOnUiThread { Toast.makeText(act, t, Toast.LENGTH_LONG).show() }

    fun shutdown() {
        io.execute { parts.keys.toList().forEach(::drop) }
        io.shutdown()
    }
}

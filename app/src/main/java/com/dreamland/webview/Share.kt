package com.dreamland.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 공유로 받기 · 파일 열기: 다른 앱(갤러리 · 카톡 · 파일 앱)에서 캐릭터 카드나 파일을 "공유 → Dreamland" 하거나
 * "Dreamland 로 열기" 하면, 드림랜드의 불러오기 화면에 그 파일을 끌어다 놓은 것처럼 넘긴다.
 *
 * 파일 내용을 앱이 복사하지 않고, 드림랜드 주소 아래 `/__dl_share/<번호>/<이름>` 으로 그대로 흘려 준다
 * (웹뷰가 그 주소를 부르면 앱이 가로채서 공유받은 파일을 읽어 줌). 크기 제한은 없다.
 * 그다음은 드림랜드의 불러오기 화면이 끌어다 놓기로 받은 파일과 똑같이 처리한다.
 */
class Share(private val ctx: Context, private val serverUrl: () -> String) {

    private class Item(val uri: Uri, val name: String, val mime: String, val size: Long)

    private val items = ConcurrentHashMap<String, Item>()
    private var next = 1
    /** 아직 드림랜드에 넘기지 못한 파일들 (드림랜드가 뜨면 넘긴다) */
    private var pending: List<String> = emptyList()

    val hasPending get() = pending.isNotEmpty()

    /** 공유 · 열기 신호에서 파일을 꺼내 둔다. 파일이 있었으면 true */
    fun take(intent: Intent?): Boolean {
        intent ?: return false
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> stream(intent)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> uris.addAll(streams(intent))
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { uris.add(it) }
            else -> return false
        }
        if (uris.isEmpty()) return false
        pending = uris.map { u ->
            val (name, size) = describe(u)
            val id = "${next++}"
            items[id] = Item(u, name, intent.type?.takeIf { it != "*/*" } ?: ctx.contentResolver.getType(u) ?: guess(name), size)
            id
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun describe(u: Uri): Pair<String, Long> {
        var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    private fun guess(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "webp" -> "image/webp"; "json" -> "application/json"; "jsonl" -> "application/jsonl"
        "zip", "charx" -> "application/zip"; else -> "application/octet-stream"
    }

    /** dreamView 의 shouldInterceptRequest 에서 부른다. 우리 주소가 아니면 null (웹뷰가 원래대로 처리) */
    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        val path = u.path ?: return null
        if (!path.startsWith("/__dl_share/")) return null
        val s = Uri.parse(serverUrl())
        fun port(x: Uri) = if (x.port > 0) x.port else if (x.scheme == "https") 443 else 80
        if (!u.scheme.equals(s.scheme, true) || !u.host.equals(s.host, true) || port(u) != port(s)) return null
        val it = items[path.removePrefix("/__dl_share/").substringBefore('/')]
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        val input = runCatching { ctx.contentResolver.openInputStream(it.uri) }.getOrNull()
            ?: return WebResourceResponse("text/plain", "utf-8", 410, "Gone", emptyMap(), "".byteInputStream())
        val headers = mutableMapOf("Cache-Control" to "no-store")
        if (it.size >= 0) headers["Content-Length"] = it.size.toString()
        return WebResourceResponse(it.mime, null, 200, "OK", headers, input)
    }

    /**
     * 드림랜드에 넘기는 스크립트: 불러오기 화면을 열고(드림랜드 라우터 방식 그대로: 기록에 쌓고 popstate),
     * 그 화면의 끌어다 놓는 상자에 파일을 놓는다. 다 넘겼으면 pending 을 비운다.
     */
    fun deliver(view: WebView, done: (Boolean) -> Unit = {}) {
        val ids = pending
        if (ids.isEmpty()) return
        pending = emptyList()
        val list = JSONArray()
        ids.forEach { id -> items[id]?.let { list.put(JSONObject().put("url", "/__dl_share/$id/" + Uri.encode(it.name)).put("name", it.name).put("mime", it.mime)) } }
        val js = """(async function(files){
  try {
    // 드림랜드가 다 켜질 때까지 (화면 목록 막대가 생기면 라우터도 준비된 것)
    for (var w = 0; w < 150 && !document.querySelector('#rail [data-page]'); w++) await new Promise(function(r){ setTimeout(r, 100); });
    if (!document.querySelector('.page-import .dropzone')) {
      history.pushState({ dl: 1, page: 'import', arg: null }, '');
      dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    }
    var zone = null;
    for (var i = 0; i < 100 && !(zone = document.querySelector('.page-import .dropzone')); i++) await new Promise(function(r){ setTimeout(r, 100); });
    if (!zone) return 'nozone';
    var dt = new DataTransfer();
    for (var f of files) { var r = await fetch(f.url); if (!r.ok) continue; var b = await r.blob(); dt.items.add(new File([b], f.name, { type: f.mime || b.type })); }
    if (!dt.files.length) return 'nofile';
    zone.scrollIntoView({ block: 'center' });
    zone.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
    return 'ok';
  } catch (e) { return 'err:' + e; }
})($list)"""
        // evaluateJavascript 는 Promise 를 기다리지 않으므로 결과는 콘솔 대신 바로 돌려받지 않는다
        view.evaluateJavascript(js) { done(true) }
    }
}

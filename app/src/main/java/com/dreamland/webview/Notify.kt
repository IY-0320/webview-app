package com.dreamland.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * 드림랜드의 알림 · 전화를 폰으로 잇는다.
 *
 *  - 알림: 드림랜드 대화 모드는 화면을 안 보고 있을 때 온 연락을 브라우저 알림(`new Notification`)으로 알린다
 *    (드림랜드 설정 "알림으로 받기"). 웹뷰에는 브라우저 알림이 없어서, 같은 모양의 Notification 을 넣어 두고
 *    받은 것을 폰 알림으로 띄운다. 권한도 크롬처럼 폰의 알림 권한을 그대로 따른다.
 *  - 전화: 드림랜드의 전화 화면(.call-veil)이 "수신 전화"(phase-ringing)가 되면 앱이 그 사실을 받아
 *    전화 알림 · 전화 화면을 띄운다. 받기 · 거절은 드림랜드 전화 화면의 그 단추를 대신 누른다.
 *
 * 드림랜드가 원래 하는 일은 그대로 둔다 (설정 · 데이터를 바꾸지 않음). 설정한 드림랜드 주소에서 온 것만 받는다.
 */
class Notify(
    private val act: AppCompatActivity,
    private val view: WebView,
    private val serverUrl: () -> String,
    private val host: Host,
) {
    interface Host {
        /** 드림랜드 화면이 지금 사람 눈에 보이는지 (앱이 앞에 있고 드림랜드가 떠 있음) */
        fun dreamShowing(): Boolean
        /** 앱이 앞에 있는지 (첫 화면이든 드림랜드든) */
        fun appFront(): Boolean
        /** 첫 화면 위에 드림랜드풍 배너 */
        fun banner(name: String, body: String, face: String, skin: String)
        /** 폰 알림 권한 묻기. 답은 onPermission 으로 */
        fun askPermission()
        /** 알림 권한 상태: granted · default · denied */
        fun permissionState(): String
    }

    companion object {
        private val SCRIPT = """(function(){
  if (window.__dlNotify || !window.DreamNotify) return; window.__dlNotify = 1;
  // 앱 첫 화면에 가려져 있을 때 "안 보는 중"으로 (크롬의 뒤 탭처럼). 실제 숨김(앱이 뒤로 감)도 그대로 따른다
  (function(){
    var forced = false, P = Document.prototype;
    var dh = Object.getOwnPropertyDescriptor(P, 'hidden'), dv = Object.getOwnPropertyDescriptor(P, 'visibilityState');
    if (!dh || !dv) return;
    Object.defineProperty(P, 'hidden', { configurable: true, get: function(){ return forced || dh.get.call(this); } });
    Object.defineProperty(P, 'visibilityState', { configurable: true, get: function(){ return forced ? 'hidden' : dv.get.call(this); } });
    window.__dlSetHidden = function(b){
      b = !!b; if (b === forced) return;
      var before = document.hidden; forced = b;
      if (document.hidden !== before) document.dispatchEvent(new Event('visibilitychange'));
    };
  })();
  var perm = 'default', waits = [], n = 0, live = {};
  function post(o){ try { DreamNotify.postMessage(JSON.stringify(o)); } catch (e) {} }
  DreamNotify.onmessage = function(e){
    var m; try { m = JSON.parse(e.data); } catch (x) { return; }
    if (m.t === 'perm') { perm = m.v; var w = waits; waits = []; w.forEach(function(f){ try { f(perm); } catch (x) {} }); }
    else if (m.t === 'click') { var it = live[m.id]; if (it) fire(it, 'click'); }
  };
  function fire(it, type){ var f = it['on' + type]; try { if (typeof f === 'function') f.call(it, new Event(type)); } catch (x) {}
    (it._l[type] || []).forEach(function(g){ try { g.call(it, new Event(type)); } catch (x) {} }); }
  // 얼굴 그림: 이미 화면에 그려진 <img> 를 작은 PNG 로 (같은 출처라 읽을 수 있다)
  function snap(img){
    try { if (!img || !img.complete || !img.naturalWidth) return '';
      var s = 160, c = document.createElement('canvas'), w = img.naturalWidth, h = img.naturalHeight, m = Math.min(w, h);
      c.width = c.height = s; c.getContext('2d').drawImage(img, (w - m) / 2, (h - m) / 2, m, m, 0, 0, s, s);
      return c.toDataURL('image/png'); } catch (e) { return ''; }
  }
  // 배경 그림으로 그린 얼굴 (드림랜드의 .avatar 는 background-image: url(blob:...))
  function load(u){
    return new Promise(function(ok){
      var i = new Image(), t = setTimeout(function(){ ok(''); }, 1500);
      i.onload = function(){ clearTimeout(t); ok(snap(i)); }; i.onerror = function(){ clearTimeout(t); ok(''); };
      i.src = u;
    });
  }
  function bg(el){ var m = (getComputedStyle(el).backgroundImage || '').match(/url\(["']?(.*?)["']?\)/); return m ? m[1] : ''; }
  // 이름이 적힌 곳 가까이의 얼굴 그림 (대화 머리 · 메시지 머리 · 대화 목록 등). 못 찾으면 앱이 첫 글자로 그린다
  function faceOf(name){
    if (!name) return Promise.resolve('');
    var all = document.querySelectorAll('b, strong, span, small, h1, h2, h3, h4, div, p, a');
    for (var i = 0; i < all.length; i++) {
      var el = all[i]; if (el.childElementCount > 2 || (el.textContent || '').trim() !== name) continue;
      for (var up = el, k = 0; up && k < 4; up = up.parentElement, k++) {
        var imgs = up.querySelectorAll('img');
        for (var j = 0; j < imgs.length; j++) { var d = snap(imgs[j]); if (d) return Promise.resolve(d); }
        var avs = up.querySelectorAll('[class*="avatar"]');
        for (var a = 0; a < avs.length; a++) { var u = bg(avs[a]); if (u) return load(u); }
      }
    }
    return Promise.resolve('');
  }
  // 이 이름이 보낸 마지막 답장의 말풍선들 (첫 말풍선이 알림 내용으로 시작하는 것). 없으면 null
  function bubbles(name, body){
    var head = String(body || '').slice(0, 20), arts = [].slice.call(document.querySelectorAll('article.msg.ai')).reverse().slice(0, 4);
    for (var i = 0; i < arts.length; i++) {
      var b = arts[i].querySelector('.msg-head b'); if (!b || b.textContent.trim() !== name) continue;
      var says = [].map.call(arts[i].querySelectorAll('.msg-body .say'), function(s){ return ((s.firstElementChild || s).textContent || '').trim(); }).filter(Boolean);
      if (says.length && (!head || says.join('\n').indexOf(head) >= 0)) return says;
    }
    return null;
  }
  // 말풍선이 하나씩 나오는 설정이면 다 나올 때까지 잠깐 기다린다 (개수가 두 번 그대로면 끝, 최대 7초)
  function settle(name, body, done){
    var last = -1, same = 0, n = 0;
    (function step(){
      var l = bubbles(name, body), c = l ? l.length : 0;
      if (c === last) same++; else { same = 0; last = c; }
      if ((l && same >= 2) || ++n > 10) return done(l);
      setTimeout(step, 700);
    })();
  }
  function N(title, opt){
    opt = opt || {};
    this._id = 'n' + (++n); this._l = {};
    this.title = String(title == null ? '' : title); this.body = String(opt.body == null ? '' : opt.body);
    this.tag = opt.tag || ''; this.icon = opt.icon || ''; this.data = opt.data === undefined ? null : opt.data;
    this.onclick = this.onclose = this.onshow = this.onerror = null;
    live[this._id] = this;
    var self = this;
    // 드림랜드 알림에는 첫 줄만 담긴다. 대화 모드 답장은 말풍선(.say) 여러 개라, 화면에 그려진 그 답장의 말풍선을 모두 모아 보낸다
    settle(this.title, this.body, function(lines){
      faceOf(self.title).then(function(face){ post({ t: 'msg', id: self._id, name: self.title, body: self.body, lines: lines || [], tag: self.tag, face: face }); });
    });
  }
  N.prototype.close = function(){ delete live[this._id]; };
  N.prototype.addEventListener = function(t, f){ (this._l[t] = this._l[t] || []).push(f); };
  N.prototype.removeEventListener = function(t, f){ var a = this._l[t] || []; var i = a.indexOf(f); if (i >= 0) a.splice(i, 1); };
  Object.defineProperty(N, 'permission', { get: function(){ return perm; } });
  N.requestPermission = function(cb){
    return new Promise(function(ok){ waits.push(function(p){ if (typeof cb === 'function') try { cb(p); } catch (x) {} ok(p); }); post({ t: 'ask' }); });
  };
  N.maxActions = 0;
  try { Object.defineProperty(window, 'Notification', { value: N, writable: true, configurable: true }); } catch (e) { window.Notification = N; }
  post({ t: 'hello' });

  // ── 전화: 드림랜드 전화 화면이 "수신 전화"가 되는지 지켜본다 ──
  // 얼굴 그림을 읽는 동안(최대 1.5초) 전화가 끝날 수 있다. "끝"이 "옴"보다 먼저 가지 않게 같은 줄(ringP)에 세운다
  var ringing = false, queued = false, ringP = Promise.resolve();
  function scan(){
    queued = false;
    var v = document.querySelector('.call-veil'), ph = v ? ((v.className.match(/\bphase-(\w+)/) || [])[1] || '') : '';
    if (ph === 'ringing' && !ringing) {
      ringing = true;
      var nm = v.querySelector('.call-name'), name = nm ? nm.textContent.trim() : '', f = snap(v.querySelector('.call-face img'));
      ringP = ringP.then(function(){ return f || faceOf(name); }).then(function(face){ post({ t: 'ring', name: name, face: face }); });
    } else if (ringing && ph !== 'ringing') {
      ringing = false;
      var end = ph || 'gone';
      ringP = ringP.then(function(){ post({ t: 'ringEnd', phase: end }); });
    }
  }
  function later(){ if (!queued) { queued = true; Promise.resolve().then(scan); } }
  new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  // 앱의 받기 · 거절 · 문자로: 드림랜드 전화 화면의 그 단추를 누른다
  window.__dlCall = function(k){
    var v = document.querySelector('.call-veil.phase-ringing'); if (!v) return false;
    var keys = [].slice.call(v.querySelectorAll('.call-end-row .call-key'));
    var b = k === 'accept' ? v.querySelector('.call-end-row .call-key.pick') : k === 'decline' ? v.querySelector('.call-end-row .call-key.hang')
      : keys.filter(function(x){ return !x.classList.contains('pick') && !x.classList.contains('hang'); })[0];
    if (!b) return false; b.click(); return true;
  };
})();"""
    }

    private val main = Handler(Looper.getMainLooper())
    private var reply: JavaScriptReplyProxy? = null
    /** 앱이 전화 알림을 울리고 있는지 (드림랜드 화면을 보고 있었으면 앱은 울리지 않는다) */
    private var ringingHere = false
    /** 앱에서 받기 · 거절을 눌렀는지 (그러면 부재중이 아니다) */
    private var handled = false

    fun attach() {
        Alerts.ensureChannels(act)
        Alerts.onDecline = { run("decline") }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addWebMessageListener(view, "DreamNotify", setOf("*")) { _, msg, origin, isMain, proxy ->
            if (!isMain || !sameOrigin(origin, Uri.parse(serverUrl()))) return@addWebMessageListener
            reply = proxy
            val o = runCatching { JSONObject(msg.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            onMessage(o)
        }
        WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
    }

    private fun sameOrigin(a: Uri, b: Uri): Boolean {
        fun port(u: Uri) = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }

    private fun send(o: JSONObject) = main.post { runCatching { reply?.postMessage(o.toString()) } }

    /** 폰 알림 권한이 바뀌었을 때 (권한 창의 답 · 앱으로 돌아왔을 때) */
    fun pushPermission() = send(JSONObject().put("t", "perm").put("v", host.permissionState()))

    private fun onMessage(o: JSONObject) {
        when (o.optString("t")) {
            "hello" -> pushPermission()
            "ask" -> if (host.permissionState() == "default") host.askPermission() else pushPermission()
            "msg" -> {
                val name = o.optString("name"); val face = o.optString("face")
                val arr = o.optJSONArray("lines")
                val lines = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }.filter { it.isNotBlank() }
                    .ifEmpty { listOf(o.optString("body")) }
                val cfg = Alerts.cfg(act)
                when {
                    !cfg.msgOn -> {}
                    host.dreamShowing() -> {}   // 보고 있으면 드림랜드가 스스로 보여 준다
                    host.appFront() && cfg.banner -> { host.banner(name, lines.joinToString("\n"), face, Alerts.skinFor(act, name, "msg")); Alerts.chime(act) }
                    else -> Alerts.message(act, name, lines, face)
                }
                if (cfg.msgOn) Alerts.remember(act, name, Alerts.avatarFromData(face))
            }
            "ring" -> {
                handled = false
                // 드림랜드 전화 화면을 보고 있으면 드림랜드가 울린다
                if (host.dreamShowing()) { ringingHere = false; return }
                ringingHere = true
                Alerts.remember(act, o.optString("name"), Alerts.avatarFromData(o.optString("face")))
                Alerts.startRing(act, o.optString("name"), o.optString("face"), foreground = host.appFront())
            }
            "ringEnd" -> {
                if (!ringingHere) return
                ringingHere = false
                val missed = !handled && o.optString("phase") != "connected"
                Alerts.endRing(act, missed = missed)
            }
        }
    }

    /** 앱의 전화 화면 · 알림에서 누른 것을 드림랜드 전화 화면에 전한다. ok = 드림랜드가 아직 울리고 있었는지 */
    fun run(what: String, ok: (Boolean) -> Unit = {}) {
        handled = true
        view.evaluateJavascript("window.__dlCall ? __dlCall('$what') : false") { r -> ok(r == "true") }
    }

    fun shutdown() {
        if (Alerts.onDecline != null) Alerts.onDecline = null
        Alerts.endRing(act, missed = false, quiet = true)
    }
}

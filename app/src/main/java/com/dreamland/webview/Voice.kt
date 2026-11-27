package com.dreamland.webview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 드림랜드의 "목소리로 읽기(브라우저 음성)" 와 "말로 입력하기" 가 앱 안에서도 되게 한다.
 *
 * 크롬에는 있지만 안드로이드 WebView 에는 없는 것:
 *  - speechSynthesis (글 읽어 주기)  → 안드로이드 TextToSpeech 로
 *  - SpeechRecognition (받아쓰기)   → WebView 것은 늘 not-allowed 라서 안드로이드 SpeechRecognizer 로
 * 드림랜드 코드는 그대로 두고, 같은 모양(웹 표준)의 대역을 페이지 시작 전에 넣는다.
 * 통로(DreamVoice)는 설정한 드림랜드 주소에서 온 것만 받는다.
 */
class Voice(
    private val act: AppCompatActivity,
    private val view: WebView,
    private val serverUrl: () -> String,
) {
    companion object {
        private val SCRIPT = """(function(){
  if (window.__dreamVoice || !window.DreamVoice) return; window.__dreamVoice = 1;
  var V = DreamVoice, n = 0, waits = {}, voices = [];
  function post(o){ try { V.postMessage(JSON.stringify(o)); } catch (e) {} }
  function Em(){}
  Em.prototype.addEventListener = function(t, f){ var l = this.__l || (this.__l = {}); (l[t] = l[t] || []).push(f); };
  Em.prototype.removeEventListener = function(t, f){ var l = this.__l && this.__l[t]; if (l) { var i = l.indexOf(f); if (i >= 0) l.splice(i, 1); } };
  function fire(o, type, extra){
    var e = { type: type, target: o }; if (extra) for (var k in extra) e[k] = extra[k];
    try { o['on' + type] && o['on' + type](e); } catch (x) { console.error(x); }
    ((o.__l && o.__l[type]) || []).slice().forEach(function(f){ try { f.call(o, e); } catch (x) { console.error(x); } });
  }
  var synth = null;
  V.onmessage = function(ev){
    var m; try { m = JSON.parse(ev.data); } catch (x) { return; }
    if (m.t === 'voices') { voices = m.list.map(function(v){ return { name: v.name, voiceURI: v.name, lang: v.lang, localService: true, 'default': !!v.def }; }); if (synth) fire(synth, 'voiceschanged'); return; }
    var h = waits[m.id]; if (h) h(m);
  };

  // ── 글 읽어 주기 ──
  if (!('speechSynthesis' in window)) {
    var U = function(text){ this.text = text == null ? '' : String(text); this.lang = ''; this.voice = null; this.rate = 1; this.pitch = 1; this.volume = 1; };
    U.prototype = Object.create(Em.prototype);
    var queue = [], cur = null;
    synth = Object.create(Em.prototype);
    synth.speaking = false; synth.pending = false; synth.paused = false;
    synth.getVoices = function(){ return voices.slice(); };
    function next(){
      cur = queue.shift() || null; synth.speaking = !!cur; synth.pending = queue.length > 0;
      if (!cur) return;
      var u = cur, id = 'u' + (++n);
      waits[id] = function(m){
        if (m.e === 'start') fire(u, 'start', { charIndex: 0, elapsedTime: 0, name: '' });
        else if (m.e === 'boundary') fire(u, 'boundary', { name: 'word', charIndex: m.i, charLength: m.l || 0, elapsedTime: 0 });
        else { delete waits[id]; if (cur === u) { fire(u, m.e === 'error' ? 'error' : 'end', { error: m.err || '', charIndex: 0, elapsedTime: 0 }); next(); } }
      };
      post({ t: 'speak', id: id, text: u.text, lang: u.lang || (u.voice && u.voice.lang) || '', voice: u.voice ? u.voice.name : '', rate: +u.rate || 1, pitch: +u.pitch || 1, volume: u.volume == null ? 1 : +u.volume });
    }
    synth.speak = function(u){ queue.push(u); synth.pending = true; if (!cur) next(); };
    synth.cancel = function(){
      var c = cur, q = queue; queue = []; cur = null; synth.speaking = false; synth.pending = false;
      post({ t: 'stop' });
      if (c) fire(c, 'error', { error: 'interrupted', charIndex: 0, elapsedTime: 0 });
      q.forEach(function(u){ fire(u, 'error', { error: 'canceled', charIndex: 0, elapsedTime: 0 }); });
    };
    synth.pause = function(){}; synth.resume = function(){};
    window.speechSynthesis = synth; window.SpeechSynthesisUtterance = U;
    post({ t: 'voices' });
  }

  // ── 말로 입력하기 ──
  var R = function(){ this.lang = ''; this.continuous = false; this.interimResults = false; this.maxAlternatives = 1; };
  R.prototype = Object.create(Em.prototype);
  R.prototype.start = function(){
    var r = this; if (r.__id) throw new DOMException('already started', 'InvalidStateError');
    var id = 'r' + (++n); r.__id = id;
    waits[id] = function(m){
      if (m.e === 'start') { fire(r, 'start'); fire(r, 'audiostart'); }
      else if (m.e === 'result') {
        var alt = { transcript: m.text, confidence: 1 }, res = [alt]; res.isFinal = !!m.final; res.item = function(i){ return res[i]; };
        var list = [res]; list.item = function(i){ return list[i]; };
        fire(r, 'result', { resultIndex: 0, results: list });
      }
      else if (m.e === 'error') fire(r, 'error', { error: m.err, message: '' });
      else if (m.e === 'end') { delete waits[id]; r.__id = null; fire(r, 'end'); }
    };
    post({ t: 'listen', id: id, lang: r.lang || navigator.language || '', interim: !!r.interimResults });
  };
  R.prototype.stop = function(){ if (this.__id) post({ t: 'listenStop' }); };
  R.prototype.abort = function(){ if (this.__id) post({ t: 'listenAbort' }); };
  window.SpeechRecognition = window.webkitSpeechRecognition = R;
})();"""
    }

    private val main = Handler(Looper.getMainLooper())
    private var reply: JavaScriptReplyProxy? = null

    fun attach() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addWebMessageListener(view, "DreamVoice", setOf("*")) { _, msg, origin, isMain, proxy ->
            if (!isMain || !sameOrigin(origin, Uri.parse(serverUrl()))) return@addWebMessageListener
            reply = proxy
            val o = runCatching { JSONObject(msg.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            when (o.optString("t")) {
                "voices" -> withTts { sendVoices() }
                "speak" -> withTts { speak(o) }
                "stop" -> tts?.stop()
                "listen" -> listen(o)
                "listenStop" -> recognizer?.stopListening()
                "listenAbort" -> abortListen()
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
    }

    private fun sameOrigin(a: Uri, b: Uri): Boolean {
        fun port(u: Uri) = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }

    private fun send(o: JSONObject) = main.post { runCatching { reply?.postMessage(o.toString()) } }

    // ── 글 읽어 주기 (TextToSpeech) ──

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsWaiting = ArrayList<() -> Unit>()

    /** 한 발화가 길면 엔진 한도에 맞춰 나눠 보낸다: 조각 id → (발화 id, 시작 글자 위치, 마지막 조각인지) */
    private data class Piece(val id: String, val offset: Int, val last: Boolean)
    private val pieces = HashMap<String, Piece>()

    private fun withTts(f: () -> Unit) {
        if (ttsReady) return f()
        ttsWaiting += f
        if (tts != null) return
        tts = TextToSpeech(act) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) { ttsWaiting.clear(); return@post }
                tts?.setOnUtteranceProgressListener(progress)
                ttsWaiting.toList().forEach { it() }
                ttsWaiting.clear()
            }
        }
    }

    private fun sendVoices() {
        val list = JSONArray()
        val def = runCatching { tts?.defaultVoice?.name }.getOrNull()
        runCatching { tts?.voices }.getOrNull()?.sortedBy { it.name }?.forEach { v ->
            if (v.isNetworkConnectionRequired && v.features?.contains("notInstalled") == true) return@forEach
            list.put(JSONObject().put("name", v.name).put("lang", v.locale.toLanguageTag()).put("def", v.name == def))
        }
        send(JSONObject().put("t", "voices").put("list", list))
    }

    private fun speak(o: JSONObject) {
        val t = tts ?: return
        val id = o.optString("id")
        val text = o.optString("text")
        val want = o.optString("voice")
        val voice = if (want.isNotEmpty()) runCatching { t.voices?.firstOrNull { it.name == want } }.getOrNull() else null
        if (voice != null) t.voice = voice
        else o.optString("lang").takeIf { it.isNotBlank() }?.let { t.language = Locale.forLanguageTag(it) }
        t.setSpeechRate(o.optDouble("rate", 1.0).toFloat().coerceIn(0.1f, 10f))
        t.setPitch(o.optDouble("pitch", 1.0).toFloat().coerceIn(0.1f, 2f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f))
        }
        val max = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(500)
        if (text.isEmpty()) {
            send(JSONObject().put("id", id).put("e", "start"))
            send(JSONObject().put("id", id).put("e", "end"))
            return
        }
        var at = 0
        var k = 0
        var mode = TextToSpeech.QUEUE_FLUSH
        while (at < text.length) {
            var end = minOf(text.length, at + max)
            if (end < text.length) {   // 될 수 있으면 문장 · 낱말 경계에서 자른다
                val cut = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', '。', ' '), end - 1)
                if (cut > at + max / 2) end = cut + 1
            }
            val pid = "$id#${k++}"
            pieces[pid] = Piece(id, at, end >= text.length)
            t.speak(text.substring(at, end), mode, params, pid)
            mode = TextToSpeech.QUEUE_ADD
            at = end
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(pid: String) {
            val p = pieces[pid] ?: return
            if (p.offset == 0) send(JSONObject().put("id", p.id).put("e", "start"))
        }

        override fun onDone(pid: String) {
            val p = pieces.remove(pid) ?: return
            if (p.last) send(JSONObject().put("id", p.id).put("e", "end"))
        }

        override fun onStop(pid: String, interrupted: Boolean) { pieces.remove(pid) }

        @Deprecated("Deprecated in Java")
        override fun onError(pid: String) = fail(pid)
        override fun onError(pid: String, errorCode: Int) = fail(pid)

        private fun fail(pid: String) {
            val p = pieces.remove(pid) ?: return
            pieces.keys.filter { it.startsWith(p.id + "#") }.forEach { pieces.remove(it) }
            send(JSONObject().put("id", p.id).put("e", "error").put("err", "synthesis-failed"))
        }

        override fun onRangeStart(pid: String, start: Int, end: Int, frame: Int) {
            val p = pieces[pid] ?: return
            send(JSONObject().put("id", p.id).put("e", "boundary").put("i", p.offset + start).put("l", end - start))
        }
    }

    // ── 말로 입력하기 (SpeechRecognizer) ──

    private var recognizer: SpeechRecognizer? = null
    private var listenId: String? = null
    private var pendingListen: JSONObject? = null

    private val micAsk = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val o = pendingListen ?: return@registerForActivityResult
        pendingListen = null
        if (ok) listen(o) else endListen(o.optString("id"), "not-allowed")
    }

    private fun listen(o: JSONObject) {
        val id = o.optString("id")
        if (!SpeechRecognizer.isRecognitionAvailable(act)) return endListen(id, "service-not-allowed")
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = o
            micAsk.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        listenId?.let { endListen(it, "aborted") }
        recognizer?.destroy()
        listenId = id
        val r = SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (listenId == id) send(JSONObject().put("id", id).put("e", "start")) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (listenId == id && text.isNotEmpty())
                    send(JSONObject().put("id", id).put("e", "result").put("text", text).put("final", false))
            }

            override fun onResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (listenId != id) return
                if (text.isNotEmpty()) send(JSONObject().put("id", id).put("e", "result").put("text", text).put("final", true))
                endListen(id, null)
            }

            override fun onError(error: Int) {
                if (listenId != id) return
                endListen(id, when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> "network"
                    SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                    SpeechRecognizer.ERROR_CLIENT -> "aborted"
                    else -> if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) "language-not-supported" else "aborted"
                })
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            o.optString("lang").takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, o.optBoolean("interim"))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { r.startListening(intent) } catch (e: Exception) { endListen(id, "audio-capture") }
    }

    private fun abortListen() {
        val id = listenId ?: return
        recognizer?.cancel()
        endListen(id, "aborted")
    }

    private fun endListen(id: String, error: String?) {
        if (error != null) send(JSONObject().put("id", id).put("e", "error").put("err", error))
        send(JSONObject().put("id", id).put("e", "end"))
        if (listenId == id) listenId = null
    }

    /** 앱을 떠날 때: 읽던 것 · 듣던 것을 멈춘다 (크롬도 탭을 닫으면 멈춤) */
    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        runCatching { recognizer?.destroy() }
        tts = null; recognizer = null
    }
}

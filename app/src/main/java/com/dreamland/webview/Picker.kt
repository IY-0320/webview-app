package com.dreamland.webview

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 드림랜드의 고르기 칸(<select>)을 누르면 뜨는 목록을 드림랜드 테마로 그린다.
 *
 * WebView 는 <select> 를 안드로이드 기본 대화상자(앱 테마색, 어두운 창)로 띄워서 드림랜드와 어울리지 않았다.
 * 그래서 누르는 순간 기본 목록 대신 페이지 안에 아래에서 올라오는 시트를 그린다.
 * 색 · 글꼴 · 모서리는 지금 드림랜드 테마의 CSS 변수(--surface, --text, --dim, --accent, --line, --r, --font)를 그대로 쓰므로
 * 테마를 바꾸면 같이 바뀐다. 고르면 값을 넣고 input · change 이벤트를 보내서 드림랜드가 원래처럼 알아챈다.
 * 여러 개 고르기(multiple) · 목록형(size>1) 은 건드리지 않는다.
 */
object Picker {
    private val SCRIPT = """(function(){
  if (window.__dlPicker) return; window.__dlPicker = 1;
  var cur = null;
  function close(){ if (!cur) return false; var c = cur; cur = null; c.classList.add('out'); setTimeout(function(){ c.remove(); }, 160); return true; }
  window.__dlPickerClose = close;
  function ok(s){ return s && s.tagName === 'SELECT' && !s.multiple && !(s.size > 1) && !s.disabled; }
  function labelOf(s){
    var l = (s.labels && s.labels[0]) || (s.closest('label') ? s.closest('label') : null);
    var t = l ? (l.querySelector('.field-label') || l).textContent : (s.getAttribute('aria-label') || s.title || '');
    return String(t || '').replace(/\s+/g, ' ').trim().slice(0, 60);
  }
  function css(){
    if (document.getElementById('dl-picker-css')) return;
    var st = document.createElement('style'); st.id = 'dl-picker-css';
    st.textContent =
      '.dl-pk{position:fixed;inset:0;z-index:2147483000;display:flex;flex-direction:column;justify-content:flex-end;background:rgba(0,0,0,.32);animation:dlpkf .16s ease-out}' +
      '.dl-pk.out{opacity:0;transition:opacity .15s}' +
      '.dl-pk-box{background:var(--surface,#fff);color:var(--text,#111);font-family:var(--font,inherit);border-radius:calc(var(--r,3px) + 10px) calc(var(--r,3px) + 10px) 0 0;max-height:72vh;overflow:auto;-webkit-overflow-scrolling:touch;padding:6px 0 max(10px,env(safe-area-inset-bottom));box-shadow:0 -8px 30px rgba(0,0,0,.18);animation:dlpku .2s cubic-bezier(.2,.8,.2,1)}' +
      '.dl-pk-h{position:sticky;top:-6px;background:var(--surface,#fff);padding:14px 22px 10px;font-size:13px;letter-spacing:.02em;color:var(--dim,#666);border-bottom:1px solid var(--line,rgba(0,0,0,.1))}' +
      '.dl-pk-g{padding:14px 22px 6px;font-size:12px;color:var(--dim,#666)}' +
      '.dl-pk-o{display:flex;align-items:center;gap:12px;width:100%;border:0;background:none;color:inherit;font:inherit;font-size:16px;text-align:left;padding:15px 22px;cursor:pointer}' +
      '.dl-pk-o+.dl-pk-o{border-top:1px solid var(--line,rgba(0,0,0,.08))}' +
      '.dl-pk-o span{flex:1;min-width:0;overflow-wrap:anywhere}' +
      '.dl-pk-o i{width:18px;height:18px;flex:none;border-radius:50%;border:1.5px solid var(--line,rgba(0,0,0,.25));box-sizing:border-box}' +
      '.dl-pk-o.on{color:var(--accent,#2447f0);font-weight:600}' +
      '.dl-pk-o.on i{border:5px solid var(--accent,#2447f0)}' +
      '.dl-pk-o:active{background:color-mix(in srgb,var(--accent,#2447f0) 8%,transparent)}' +
      '.dl-pk-o[disabled]{opacity:.4}' +
      '@keyframes dlpkf{from{opacity:0}}@keyframes dlpku{from{transform:translateY(24px);opacity:.6}}' +
      '.dl-sg{position:fixed;z-index:2147482999;overflow:auto;-webkit-overflow-scrolling:touch;background:var(--surface,#fff);color:var(--text,#111);font-family:var(--font,inherit);border:1px solid var(--line,rgba(0,0,0,.12));border-radius:calc(var(--r,3px) + 6px);box-shadow:0 10px 28px rgba(0,0,0,.16);padding:4px 0}' +
      '.dl-sg-o{display:flex;flex-direction:column;align-items:flex-start;gap:2px;width:100%;border:0;background:none;color:inherit;font:inherit;font-size:15px;text-align:left;padding:11px 16px;cursor:pointer}' +
      '.dl-sg-o small{font-size:12px;color:var(--dim,#666)}' +
      '.dl-sg-o.on{color:var(--accent,#2447f0);font-weight:600}' +
      '.dl-sg-o:active{background:color-mix(in srgb,var(--accent,#2447f0) 8%,transparent)}';
    (document.head || document.documentElement).appendChild(st);
  }
  function open(s){
    close(); css();
    var wrap = document.createElement('div'); wrap.className = 'dl-pk';
    var box = document.createElement('div'); box.className = 'dl-pk-box'; box.setAttribute('role', 'listbox');
    var title = labelOf(s); if (title) { var h = document.createElement('div'); h.className = 'dl-pk-h'; h.textContent = title; box.appendChild(h); }
    var picked = null;
    [].forEach.call(s.children, function(node){
      var group = node.tagName === 'OPTGROUP';
      if (group) { var g = document.createElement('div'); g.className = 'dl-pk-g'; g.textContent = node.label; box.appendChild(g); }
      [].forEach.call(group ? node.children : [node], function(o){
        if (o.tagName !== 'OPTION' || o.hidden) return;
        var b = document.createElement('button'); b.type = 'button'; b.className = 'dl-pk-o' + (o.selected ? ' on' : '');
        b.setAttribute('role', 'option'); b.setAttribute('aria-selected', String(o.selected));
        if (o.disabled || (group && node.disabled)) b.disabled = true;
        var t = document.createElement('span'); t.textContent = o.label || o.textContent; b.appendChild(t); b.appendChild(document.createElement('i'));
        b.addEventListener('click', function(){
          close();
          if (o.selected) return;
          o.selected = true;
          s.dispatchEvent(new Event('input', { bubbles: true }));
          s.dispatchEvent(new Event('change', { bubbles: true }));
        });
        if (o.selected) picked = b;
        box.appendChild(b);
      });
    });
    wrap.appendChild(box);
    wrap.addEventListener('click', function(e){ if (e.target === wrap) close(); });
    document.body.appendChild(wrap); cur = wrap;
    if (picked) picked.scrollIntoView({ block: 'center' });
  }
  // 기본 목록이 뜨지 않게 누르는 순간을 막고, 손을 떼면(click) 시트를 연다
  function block(e){ if (ok(e.target)) { e.preventDefault(); } }
  document.addEventListener('mousedown', block, true);
  document.addEventListener('pointerdown', function(e){ if (ok(e.target) && e.pointerType !== 'mouse') e.target.__dlTouch = 1; }, true);
  document.addEventListener('click', function(e){ var s = e.target; if (!ok(s)) return; e.preventDefault(); s.focus({ preventScroll: true }); open(s); }, true);
  // 키보드(다음 칸 이동)로 와서 엔터 · 스페이스를 누를 때도 같은 시트
  document.addEventListener('keydown', function(e){ var s = e.target; if (ok(s) && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); open(s); } }, true);

  // ── 입력칸 추천 목록 (<input list> + <datalist>) ──
  // WebView 의 기본 추천 창은 키보드가 올라와 화면이 줄어도 처음 자리에 남는다 (크롬은 따라 움직임).
  // 그래서 칸에 들어가는 동안만 list 속성을 잠시 떼어 기본 창을 막고, 칸 바로 밑(자리가 없으면 위)에 테마색 목록을 그린다.
  var sug = null, sugFor = null;
  function sugClose(){ if (!sug) return false; sug.remove(); sug = null; return true; }
  window.__dlSuggestClose = function(){ var had = sugClose(); if (sugFor) { sugFor.blur(); } return had; };
  function optionsOf(inp){
    var dl = document.getElementById(inp.__dlList || ''); if (!dl) return [];
    return [].map.call(dl.querySelectorAll('option'), function(o){ return { v: o.value, l: o.label && o.label !== o.value ? o.label : '' }; })
      .filter(function(o){ return o.v; });
  }
  function setValue(inp, v){
    var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    d.set.call(inp, v);   // React 가 바뀐 값을 알아채게 원래 setter 로 넣는다
    inp.dispatchEvent(new Event('input', { bubbles: true }));
    inp.dispatchEvent(new Event('change', { bubbles: true }));
  }
  function place(){
    if (!sug || !sugFor) return;
    var r = sugFor.getBoundingClientRect(), vv = window.visualViewport, top0 = vv ? vv.offsetTop : 0, h = vv ? vv.height : innerHeight;
    var below = top0 + h - r.bottom - 8, above = r.top - top0 - 8, max = Math.max(below, above);
    sug.style.left = Math.max(8, r.left) + 'px'; sug.style.width = Math.max(180, r.width) + 'px';
    sug.style.maxHeight = Math.max(96, Math.min(280, max)) + 'px';
    if (below >= Math.min(180, sug.scrollHeight) || below >= above) { sug.style.top = (r.bottom + 4) + 'px'; sug.style.bottom = ''; }
    else { sug.style.top = ''; sug.style.bottom = (innerHeight - r.top + 4) + 'px'; }
  }
  function sugShow(inp){
    css();
    var q = String(inp.value || '').toLowerCase();
    var all = optionsOf(inp), list = all.filter(function(o){ return !q || o.v.toLowerCase().indexOf(q) >= 0 || o.l.toLowerCase().indexOf(q) >= 0; });
    // 이미 목록의 값 하나를 그대로 넣어 둔 상태면 전체를 보여 준다 (크롬도 같은 값이면 다른 것도 고를 수 있게 보여 줌)
    if (list.length === 1 && list[0].v === inp.value) list = all;
    if (!list.length) { sugClose(); return; }
    if (!sug) {
      sug = document.createElement('div'); sug.className = 'dl-sg'; sug.setAttribute('role', 'listbox');
      sug.addEventListener('pointerdown', function(e){ e.preventDefault(); });   // 누르는 동안 칸의 초점(키보드)이 빠지지 않게
      document.body.appendChild(sug);
    }
    sug.textContent = '';
    list.forEach(function(o){
      var b = document.createElement('button'); b.type = 'button'; b.className = 'dl-sg-o' + (o.v === inp.value ? ' on' : '');
      var t = document.createElement('span'); t.textContent = o.v; b.appendChild(t);
      if (o.l) { var s = document.createElement('small'); s.textContent = o.l; b.appendChild(s); }
      b.addEventListener('click', function(){ setValue(inp, o.v); sugClose(); });
      sug.appendChild(b);
    });
    place();
  }
  function enter(inp){
    if (!inp.__dlList) { inp.__dlList = inp.getAttribute('list'); inp.removeAttribute('list'); }
    sugFor = inp; sugShow(inp);
  }
  function leave(inp){
    if (inp.__dlList) { inp.setAttribute('list', inp.__dlList); inp.__dlList = null; }
    if (sugFor === inp) { sugFor = null; sugClose(); }
  }
  document.addEventListener('focusin', function(e){ var t = e.target; if (t && t.tagName === 'INPUT' && t.getAttribute('list')) enter(t); }, true);
  document.addEventListener('focusout', function(e){ var t = e.target; if (t && (t.__dlList || t === sugFor)) leave(t); }, true);
  document.addEventListener('input', function(e){ if (e.target === sugFor) sugShow(sugFor); }, true);
  document.addEventListener('keydown', function(e){ if (e.target === sugFor && e.key === 'Escape' && sug) { e.stopImmediatePropagation(); sugClose(); } }, true);
  addEventListener('resize', place); addEventListener('scroll', place, true);
  if (window.visualViewport) { visualViewport.addEventListener('resize', place); visualViewport.addEventListener('scroll', place); }
})();"""

    fun attach(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
    }
}

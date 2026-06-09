'use strict';
const $ = (s) => document.querySelector(s);
const api = (p, opts) => fetch(p, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts));

// 当前查询状态
const state = { q: '', group: 0, theme: 0, sort: 'new', source: 'all', page: 1, loading: false, end: false };
let imgSecret = '1';   // 图片是否 XOR 加密（从 /api/me 取）

// 封面懒加载：进视口才拉，加密则 fetch+XOR 解密成 blob
const io = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    io.unobserve(e.target);
    loadCover(e.target, e.target.dataset.cover);
  }
}, { rootMargin: '400px' });

async function loadCover(img, url) {
  if (!url) return;
  if (imgSecret !== '1') { img.src = url; return; }
  try {
    const buf = new Uint8Array(await (await fetch(url)).arrayBuffer());
    const k = buf[0];
    for (let i = 1; i < buf.length; i++) buf[i] ^= k;   // 每字节 XOR 首字节
    img.src = URL.createObjectURL(new Blob([buf.slice(1)], { type: 'image/png' }));  // 丢首字节
  } catch (e) { img.style.opacity = 0.2; }
}

// ---------- 登录 ----------
async function checkAuth() {
  const r = await api('/api/me').then(r => r.json());
  if (r.auth) { $('#login').classList.add('hidden'); init(); }
  else { $('#login').classList.remove('hidden'); }
}
async function doLogin() {
  const r = await api('/api/login', { method: 'POST', body: JSON.stringify({ password: $('#pw').value }) });
  if (r.ok) { $('#login').classList.add('hidden'); init(); }
  else { $('#login-err').textContent = '密码错误'; }
}
$('#login-btn').onclick = doLogin;
$('#pw').addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
$('#logout').onclick = async () => { await api('/api/logout', { method: 'POST' }); location.reload(); };

// ---------- 列表 ----------
const fmtDur = (s) => { s = s || 0; const m = Math.floor(s / 60), x = s % 60; return `${m}:${String(x).padStart(2, '0')}`; };
const fmtNum = (n) => n >= 10000 ? (n / 10000).toFixed(1) + 'w' : (n || 0);
const fmtDate = (ms) => { if (!ms) return ''; const d = new Date(+ms); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

function reset() { state.page = 1; state.end = false; $('#grid').innerHTML = ''; load(); }

async function load() {
  if (state.loading || state.end) return;
  state.loading = true;
  $('#status').textContent = '加载中…';
  const p = new URLSearchParams({
    q: state.q, group: state.group, theme: state.theme, sort: state.sort, source: state.source,
    page: state.page, page_size: 30,
  });
  try {
    const data = await api('/api/videos?' + p).then(r => r.json());
    for (const v of data.items) $('#grid').appendChild(card(v));
    state.end = !data.has_more;
    state.page++;
    $('#status').textContent = state.end ? (($('#grid').children.length) ? '到底了' : '没有内容') : '';
  } catch (e) {
    $('#status').textContent = '加载失败';
  }
  state.loading = false;
}

function card(v) {
  const el = document.createElement('div');
  el.className = 'card';
  el.innerHTML = `
    <div class="thumb">
      <img class="cover" data-cover="${v.cover}">
      <span class="dur">${fmtDur(v.duration)}</span>
    </div>
    <div class="title">${esc(v.title || '')}</div>
    <div class="sub"><span>${esc(v.author || '')}</span><span>▶ ${fmtNum(v.readNumber)}</span></div>
    <div class="sub"><span>${fmtDate(v.createTime)}</span></div>`;
  el.onclick = () => openPlayer(v.id);
  io.observe(el.querySelector('img.cover'));
  return el;
}
const esc = (s) => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// 无限滚动
window.addEventListener('scroll', () => {
  if (window.innerHeight + window.scrollY >= document.body.offsetHeight - 600) load();
});

// ---------- 筛选控件（都改 hash，由 route 应用；在首页时切到「全部」浏览）----------
function browse(patch) {
  if (parseHash().source === 'home') patch.source = 'all';   // 首页不支持筛选，切到全部
  setHash(patch);
}
let searchTimer, composing = false;
function doSearch(val) {
  clearTimeout(searchTimer);
  // 搜索一律全局（切到"全部"），否则在收藏/历史页只在那一小撮里搜，像搜不到
  searchTimer = setTimeout(() => browse({ q: val.trim(), source: 'all', v: null, theme: null }), 350);
}
$('#search').addEventListener('compositionstart', () => composing = true);
$('#search').addEventListener('compositionend', e => { composing = false; doSearch(e.target.value); });  // 中文组词完成才搜
$('#search').addEventListener('input', e => { if (!composing) doSearch(e.target.value); });               // 组词中不搜
$('#group').onchange = e => browse({ g: +e.target.value, v: null, theme: null });
$('#sort').onchange = e => browse({ sort: e.target.value, v: null });
document.querySelectorAll('header nav a, .brand').forEach(a => {
  a.onclick = () => setHash({ source: a.dataset.source, v: null });
});

// ---------- hash 路由 ----------
function parseHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  return { q: p.get('q') || '', source: p.get('source') || 'home', sort: p.get('sort') || 'new', g: +(p.get('g') || 0), theme: +(p.get('theme') || 0), v: p.get('v') || null };
}
function setHash(patch) {
  const o = Object.assign(parseHash(), patch);
  const p = new URLSearchParams();
  if (o.q) p.set('q', o.q);
  if (o.source && o.source !== 'home') p.set('source', o.source);
  if (o.sort && o.sort !== 'new') p.set('sort', o.sort);
  if (o.g) p.set('g', o.g);
  if (o.theme) p.set('theme', o.theme);
  if (o.v) p.set('v', o.v);
  const h = p.toString();
  if (h !== location.hash.slice(1)) location.hash = h; else route();
}
function syncControls(h) {
  if ($('#search').value !== h.q) $('#search').value = h.q;
  $('#sort').value = h.sort; $('#group').value = h.g;
  document.querySelectorAll('header nav a').forEach(x => x.classList.toggle('active', x.dataset.source === h.source));
}
let curView = null;
let themeMap = {};   // 专题 id->名，点专题进列表时面包屑显示用
function route() {
  const h = parseHash();
  syncControls(h);
  const isHome = h.source === 'home';
  $('#home').classList.toggle('hidden', !isHome);
  $('#grid').classList.toggle('hidden', isHome);
  $('#status').classList.toggle('hidden', isHome);
  renderCrumb(h);
  if (isHome) {
    if (curView !== 'home') { curView = 'home'; renderHome(); }
  } else {
    const changed = h.q !== state.q || h.source !== state.source || h.sort !== state.sort || h.g !== state.group || h.theme !== state.theme;
    state.q = h.q; state.source = h.source; state.sort = h.sort; state.group = h.g; state.theme = h.theme;
    if (curView !== 'grid' || changed) { curView = 'grid'; reset(); }
  }
  if (h.v) _openPlayer(+h.v); else _closePlayer();
}
function renderCrumb(h) {
  const el = $('#crumb');
  if (!h.theme || h.source === 'home') { el.classList.add('hidden'); return; }
  el.innerHTML = `<a class="back">← 返回首页</a><span>专题 · ${esc(themeMap[h.theme] || '合集')}</span>`;
  el.querySelector('.back').onclick = () => setHash({ source: 'home', theme: null, q: '', g: 0, v: null });
  el.classList.remove('hidden');
}
window.addEventListener('hashchange', route);

// ---------- 首页：分组聚合（代理 theme/index）----------
async function renderHome() {
  const home = $('#home');
  home.innerHTML = '<div class="status">加载中…</div>';
  try {
    const themes = await api('/api/home').then(r => r.json());
    home.innerHTML = '';
    for (const t of themes) {
      const sec = document.createElement('div');
      sec.className = 'theme';
      sec.innerHTML = `<h3>${esc(t.title || '')} ›</h3><div class="row"></div>`;
      sec.querySelector('h3').onclick = () => setHash({ source: 'all', theme: t.id, q: '', g: 0, v: null });
      const row = sec.querySelector('.row');
      for (const v of t.items) row.appendChild(card(v));
      home.appendChild(sec);
    }
    if (!themes.length) home.innerHTML = '<div class="status">首页暂无内容</div>';
  } catch (e) { home.innerHTML = '<div class="status">加载失败</div>'; }
}

// ---------- 播放器 ----------
let hls = null;
const video = $('#video');
let curId = null;
let lineList = [], playOrder = [], slot = 0;
const isOversea = (n) => /海/.test(n || '');               // 海线判定（排在国线后）

function toast(msg) {
  let t = $('#toast');
  if (!t) { t = document.createElement('div'); t.id = 'toast'; document.body.appendChild(t); }
  t.textContent = msg; t.className = 'toast show';
  clearTimeout(toast._t); toast._t = setTimeout(() => { t.className = 'toast'; }, 2600);
}
function playLine(idx, keepTime) {                         // 播指定线路（lineList 原始下标），保留进度
  if (idx == null || !lineList[idx]) return;
  $('#line-sel').value = idx;
  const t = keepTime ? video.currentTime : 0;
  playSource(lineList[idx].url);
  if (keepTime && t > 1) video.addEventListener('loadedmetadata',
    () => { try { video.currentTime = t; } catch (e) {} }, { once: true });
}

// ---- 测速选线（手动按钮触发，不自动切，避免误判）----
function firstSegUrl(m3u8Text, baseUrl) {                  // m3u8 里第一个分片的绝对地址
  for (const ln of m3u8Text.split('\n')) {
    const s = ln.trim();
    if (s && !s.startsWith('#')) return new URL(s, baseUrl).href;
  }
  return null;
}
async function probeLine(idx, ms = 1500) {                 // 拉第一个分片，限时读流计字节 → KB/s
  try {
    const m3u8 = lineList[idx].url;
    const txt = await fetch(m3u8, { cache: 'no-store' }).then(r => r.text());
    const seg = firstSegUrl(txt, m3u8);
    if (!seg) return { idx, kbps: 0 };
    const ctrl = new AbortController();
    const resp = await fetch(seg, { signal: ctrl.signal, cache: 'no-store' });
    const reader = resp.body.getReader();
    const t0 = performance.now(); let bytes = 0;
    while (performance.now() - t0 < ms) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.length;
    }
    ctrl.abort();
    const sec = (performance.now() - t0) / 1000;
    return { idx, kbps: sec > 0 ? (bytes / 1024) / sec : 0 };
  } catch (e) { return { idx, kbps: 0 }; }
}
async function probeAndSwitch() {                          // 只测国线，逐条测（各自独占带宽更准），切到最快
  const btn = $('#probe-btn');
  const cands = playOrder.filter(i => !isOversea(lineList[i].name));
  const list = cands.length ? cands : playOrder;
  if (list.length < 2) { toast('只有一条线路，无需测速'); return; }
  btn.disabled = true; const old = btn.textContent; btn.textContent = '测速中…';
  const results = [];
  for (const idx of list) results.push(await probeLine(idx));
  btn.disabled = false; btn.textContent = old;
  const ok = results.filter(r => r.kbps > 0).sort((a, b) => b.kbps - a.kbps);
  if (!ok.length) { toast('测速失败，请手动切换线路'); return; }
  const best = ok[0];
  slot = Math.max(0, playOrder.indexOf(best.idx));
  toast(`最快「${lineList[best.idx].name}」≈${best.kbps.toFixed(0)} KB/s，已切换`);
  playLine(best.idx, true);
}
$('#probe-btn').onclick = probeAndSwitch;

function openPlayer(id) { setHash({ v: id }); }          // 改 hash，由 route 打开
function closePlayer() {                                  // × / Esc / 点外面
  if (parseHash().v) history.back(); else _closePlayer(); // 退一格历史 → hashchange → 关
}

async function _openPlayer(id) {
  if (curId === id && !$('#player').classList.contains('hidden')) return;  // 已在放同一个
  curId = id;
  const res = await api('/api/video/' + id);
  if (!res.ok) { curId = null; closePlayer(); alert('该视频暂不可用，可能已下架'); return; }
  const v = await res.json();
  $('#p-title').textContent = v.title || '';
  $('#p-stats').textContent = `▶ ${fmtNum(v.readNumber)}  ♥ ${fmtNum(v.likeNumber)}  ·  ${fmtDate(v.createTime)}`;
  $('#p-tags').innerHTML = (v.tags || []).map(t => `<span>${esc(t)}</span>`).join('');
  $('#p-tags').querySelectorAll('span').forEach(s => s.onclick = () => setHash({ q: s.textContent, source: 'all', v: null }));
  const favBtn = $('#fav-btn');
  favBtn.classList.toggle('on', v.fav);
  favBtn.textContent = v.fav ? '★ 已收藏' : '☆ 收藏';
  favBtn.onclick = async () => {
    const r = await api('/api/favorite/' + id, { method: 'POST' }).then(r => r.json());
    favBtn.classList.toggle('on', r.fav);
    favBtn.textContent = r.fav ? '★ 已收藏' : '☆ 收藏';
  };
  const fname = (v.title || id).replace(/[\\/:*?"<>|]/g, '_').slice(0, 40);
  $('#dl-btn').onclick = (e) => { e.preventDefault(); downloadHls(lines[+sel.value].url, fname, $('#dl-btn')); };

  // 线路：国线优先、海线垫底（自动只在国线间切，海线仅手动/最后兜底）
  const sel = $('#line-sel');
  const lines = v.lines && v.lines.length ? v.lines : (v.sources || []).map((u, i) => ({ name: '线路' + (i + 1), url: u }));
  if (!lines.length) { closePlayer(); alert('暂时无法播放：可能已下架或接口繁忙，稍后再试'); return; }  // 无播放源/取地址失败
  lineList = lines;
  const guo = lines.map((l, i) => i).filter(i => !isOversea(lines[i].name));
  const hai = lines.map((l, i) => i).filter(i => isOversea(lines[i].name));
  playOrder = guo.concat(hai);                             // 国线在前、海线在后；缺哪类就用现有的
  sel.innerHTML = playOrder.map(i => `<option value="${i}">${esc(lines[i].name)}</option>`).join('');
  sel.style.display = playOrder.length > 1 ? '' : 'none';
  sel.onchange = () => { slot = Math.max(0, playOrder.indexOf(+sel.value)); playLine(+sel.value, true); };

  slot = 0;
  playLine(playOrder[0], false);
  api('/api/history/' + id, { method: 'POST' });   // 记历史
  $('#player').classList.remove('hidden');
}

function playSource(src) {
  if (!src) return;
  if (hls) { hls.destroy(); hls = null; }
  if (window.Hls && Hls.isSupported()) {       // 优先 hls.js（Chrome/安卓）
    // 大缓冲：提前多缓，扛住网络抖动少卡顿
    hls = new Hls({ maxBufferLength: 90, maxMaxBufferLength: 300, maxBufferSize: 60 * 1000 * 1000 });
    hls.on(Hls.Events.ERROR, (e, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR) { try { hls.recoverMediaError(); return; } catch (_) {} }
      toast('该线路连不上，点「⚡测速选线」换一条');   // 不自动切，提示用户
    });
    hls.loadSource(src);
    hls.attachMedia(video);
  } else {                                       // Safari/iOS 原生 HLS
    video.src = src;
  }
  video.play().catch(() => {});
}

function _closePlayer() {
  if ($('#player').classList.contains('hidden')) return;
  curId = null;
  $('#player').classList.add('hidden');
  video.pause();
  if (hls) { hls.destroy(); hls = null; }
  video.removeAttribute('src'); video.load();
}
$('#close-player').onclick = closePlayer;
document.addEventListener('keydown', e => { if (e.key === 'Escape') closePlayer(); });

// ---------- 客户端直连下载（拉分片 + AES-128 解密 + 拼 .ts）----------
function hexToBytes(h) { const a = new Uint8Array(h.length / 2); for (let i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i * 2, 2), 16); return a; }

async function downloadHls(m3u8Url, fname, btn) {
  if (btn.dataset.busy) return;
  btn.dataset.busy = '1';
  const orig = '⬇ 下载';
  try {
    btn.textContent = '解析…';
    const txt = await (await fetch(m3u8Url)).text();
    const base = m3u8Url.replace(/[^/]*$/, '');
    let key = null, iv = new Uint8Array(16), hasIv = false;
    const segs = [];
    for (const raw of txt.split('\n')) {
      const l = raw.trim();
      if (l.startsWith('#EXT-X-KEY')) {
        const u = l.match(/URI="([^"]+)"/), ivm = l.match(/IV=0x([0-9a-fA-F]+)/);
        if (u) { const kb = await (await fetch(u[1])).arrayBuffer(); key = await crypto.subtle.importKey('raw', kb, 'AES-CBC', false, ['decrypt']); }
        if (ivm) { iv = hexToBytes(ivm[1]); hasIv = true; }
      } else if (l && !l.startsWith('#')) {
        segs.push(l.startsWith('http') ? l : base + l);
      }
    }
    if (!segs.length) throw new Error('no segments');
    const parts = [];
    for (let i = 0; i < segs.length; i++) {
      let buf = await (await fetch(segs[i])).arrayBuffer();
      if (key) {
        let useIv = iv;
        if (!hasIv) { useIv = new Uint8Array(16); new DataView(useIv.buffer).setUint32(12, i); }  // 无显式IV则用分片序号
        buf = await crypto.subtle.decrypt({ name: 'AES-CBC', iv: useIv }, key, buf);
      }
      parts.push(new Uint8Array(buf));
      btn.textContent = `下载 ${Math.round((i + 1) / segs.length * 100)}%`;
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob(parts, { type: 'video/mp2t' }));
    a.download = fname + '.ts';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 15000);
    btn.textContent = '✓ 已下载';
  } catch (e) {
    btn.textContent = '下载失败';
  }
  setTimeout(() => { btn.textContent = orig; delete btn.dataset.busy; }, 2500);
}

// ---------- 初始化 ----------
async function init() {
  try { imgSecret = (await api('/api/me').then(r => r.json())).img_secret || '1'; } catch (e) {}
  // 分类下拉
  try {
    const gs = await api('/api/groups').then(r => r.json());
    for (const g of gs) {
      const o = document.createElement('option');
      o.value = g.id; o.textContent = g.name;
      $('#group').appendChild(o);
    }
  } catch (e) {}
  // 专题字典（id->title），点专题进列表时面包屑显示专题名
  try { themeMap = await api('/api/themes').then(r => r.json()); } catch (e) {}
  // 深链直接进视频时，垫一个历史条目，让后退能回上一层而不是退站
  if (parseHash().v) {
    const full = location.hash;
    history.replaceState(null, '', location.pathname + location.search);
    history.pushState(null, '', full);
  }
  route();   // 渲染首页/网格 + 打开视频（如有）
}

checkAuth();

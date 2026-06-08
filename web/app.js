'use strict';
const $ = (s) => document.querySelector(s);
const api = (p, opts) => fetch(p, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts));

// 当前查询状态
const state = { q: '', group: 0, sort: 'new', source: 'all', page: 1, loading: false, end: false };
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
    q: state.q, group: state.group, sort: state.sort, source: state.source,
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
let searchTimer;
$('#search').addEventListener('input', e => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => browse({ q: e.target.value.trim(), v: null }), 350);
});
$('#group').onchange = e => browse({ g: +e.target.value, v: null });
$('#sort').onchange = e => browse({ sort: e.target.value, v: null });
document.querySelectorAll('header nav a, .brand').forEach(a => {
  a.onclick = () => setHash({ source: a.dataset.source, v: null });
});

// ---------- hash 路由 ----------
function parseHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  return { q: p.get('q') || '', source: p.get('source') || 'home', sort: p.get('sort') || 'new', g: +(p.get('g') || 0), v: p.get('v') || null };
}
function setHash(patch) {
  const o = Object.assign(parseHash(), patch);
  const p = new URLSearchParams();
  if (o.q) p.set('q', o.q);
  if (o.source && o.source !== 'home') p.set('source', o.source);
  if (o.sort && o.sort !== 'new') p.set('sort', o.sort);
  if (o.g) p.set('g', o.g);
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
function route() {
  const h = parseHash();
  syncControls(h);
  const isHome = h.source === 'home';
  $('#home').classList.toggle('hidden', !isHome);
  $('#grid').classList.toggle('hidden', isHome);
  $('#status').classList.toggle('hidden', isHome);
  if (isHome) {
    if (curView !== 'home') { curView = 'home'; renderHome(); }
  } else {
    const changed = h.q !== state.q || h.source !== state.source || h.sort !== state.sort || h.g !== state.group;
    state.q = h.q; state.source = h.source; state.sort = h.sort; state.group = h.g;
    if (curView !== 'grid' || changed) { curView = 'grid'; reset(); }
  }
  if (h.v) _openPlayer(+h.v); else _closePlayer();
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
      sec.innerHTML = `<h3>${esc(t.title || '')}</h3><div class="row"></div>`;
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

function openPlayer(id) { setHash({ v: id }); }          // 改 hash，由 route 打开
function closePlayer() {                                  // × / Esc / 点外面
  if (parseHash().v) history.back(); else _closePlayer(); // 退一格历史 → hashchange → 关
}

async function _openPlayer(id) {
  if (curId === id && !$('#player').classList.contains('hidden')) return;  // 已在放同一个
  curId = id;
  const v = await api('/api/video/' + id).then(r => r.json());
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

  // 线路选择
  const sel = $('#line-sel');
  const lines = v.lines && v.lines.length ? v.lines : (v.sources || []).map((u, i) => ({ name: '线路' + (i + 1), url: u }));
  sel.innerHTML = lines.map((l, i) => `<option value="${i}">${esc(l.name)}</option>`).join('');
  sel.style.display = lines.length > 1 ? '' : 'none';
  sel.onchange = () => {
    const t = video.currentTime;
    playSource(lines[+sel.value].url);
    video.addEventListener('loadedmetadata', () => { try { video.currentTime = t; } catch (e) {} }, { once: true });
  };

  playSource(lines.length ? lines[0].url : null);
  api('/api/history/' + id, { method: 'POST' });   // 记历史
  $('#player').classList.remove('hidden');
}

function playSource(src) {
  if (!src) return;
  if (hls) { hls.destroy(); hls = null; }
  if (window.Hls && Hls.isSupported()) {       // 优先 hls.js（Chrome/安卓）
    hls = new Hls({ maxBufferLength: 30 });
    hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) console.error('HLS', d.type, d.details); });
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
$('#player').addEventListener('click', e => { if (e.target.id === 'player') closePlayer(); });
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
  // 深链直接进视频时，垫一个历史条目，让后退能回上一层而不是退站
  if (parseHash().v) {
    const full = location.hash;
    history.replaceState(null, '', location.pathname + location.search);
    history.pushState(null, '', full);
  }
  route();   // 渲染首页/网格 + 打开视频（如有）
}

checkAuth();

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
    <div class="sub"><span>${esc(v.author || '')}</span><span>▶ ${fmtNum(v.readNumber)}</span></div>`;
  el.onclick = () => openPlayer(v.id);
  io.observe(el.querySelector('img.cover'));
  return el;
}
const esc = (s) => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// 无限滚动
window.addEventListener('scroll', () => {
  if (window.innerHeight + window.scrollY >= document.body.offsetHeight - 600) load();
});

// ---------- 筛选控件 ----------
let searchTimer;
$('#search').addEventListener('input', e => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => { state.q = e.target.value.trim(); reset(); }, 350);
});
$('#group').onchange = e => { state.group = +e.target.value; reset(); };
$('#sort').onchange = e => { state.sort = e.target.value; reset(); };
document.querySelectorAll('header nav a, .brand').forEach(a => {
  a.onclick = () => {
    state.source = a.dataset.source;
    document.querySelectorAll('header nav a').forEach(x => x.classList.toggle('active', x.dataset.source === state.source));
    reset();
  };
});

// ---------- 播放器 ----------
let hls = null;
const video = $('#video');
let curId = null;

async function openPlayer(id) {
  curId = id;
  const v = await api('/api/video/' + id).then(r => r.json());
  $('#p-title').textContent = v.title || '';
  $('#p-stats').textContent = `▶ ${fmtNum(v.readNumber)}  ♥ ${fmtNum(v.likeNumber)}`;
  $('#p-tags').innerHTML = (v.tags || []).map(t => `<span>${esc(t)}</span>`).join('');
  $('#p-tags').querySelectorAll('span').forEach(s => s.onclick = () => {
    closePlayer(); $('#search').value = s.textContent; state.q = s.textContent; reset();
  });
  const favBtn = $('#fav-btn');
  favBtn.classList.toggle('on', v.fav);
  favBtn.textContent = v.fav ? '★ 已收藏' : '☆ 收藏';
  favBtn.onclick = async () => {
    const r = await api('/api/favorite/' + id, { method: 'POST' }).then(r => r.json());
    favBtn.classList.toggle('on', r.fav);
    favBtn.textContent = r.fav ? '★ 已收藏' : '☆ 收藏';
  };
  $('#dl-btn').href = '/api/download/' + id;

  const src = (v.sources || [])[0];
  playSource(src);
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

function closePlayer() {
  $('#player').classList.add('hidden');
  video.pause();
  if (hls) { hls.destroy(); hls = null; }
  video.removeAttribute('src'); video.load();
}
$('#close-player').onclick = closePlayer;
$('#player').addEventListener('click', e => { if (e.target.id === 'player') closePlayer(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') closePlayer(); });

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
  reset();
}

checkAuth();

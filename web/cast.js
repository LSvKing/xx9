'use strict';
/**
 * xx9 投屏 UI（DOM B–F）+ 原生 XX9Cast 桥。
 * 浏览器无原生桥时：点投屏给出明确失败文案（不 mock 设备）。
 */
(function () {
  const $ = (s) => document.querySelector(s);
  const callbacks = Object.create(null);
  let cbSeq = 0;
  let devices = [];
  let casting = null; // { id, name }
  let paused = false;
  let lineDirty = false;
  let pauseSupported = true;

  function hasNative() {
    return !!(window.XX9Cast && typeof window.XX9Cast.scan === 'function');
  }

  function callNative(method, args) {
    return new Promise((resolve) => {
      if (!hasNative()) {
        resolve({ ok: false, error: 'NO_NATIVE' });
        return;
      }
      const id = 'c' + (++cbSeq);
      callbacks[id] = resolve;
      try {
        window.XX9Cast[method](...args, id);
      } catch (e) {
        delete callbacks[id];
        resolve({ ok: false, error: String(e && e.message || e) });
      }
      setTimeout(() => {
        if (callbacks[id]) {
          delete callbacks[id];
          resolve({ ok: false, error: '超时' });
        }
      }, 20000);
    });
  }

  window.xx9CastCallback = function (payload) {
    const id = payload && payload.id;
    if (!id || !callbacks[id]) return;
    const fn = callbacks[id];
    delete callbacks[id];
    fn(payload);
  };
  window.xx9CastNativeReady = function () { /* MainActivity 注入后可刷新入口态 */ };

  /** 原生 CastState 推送（Discovering/Connecting/Playing/Failed/Idle） */
  window.xx9CastOnState = function (payload) {
    const st = payload && payload.state;
    if (!st || !st.label) return;
    applyNativeState(st);
  };

  function applyNativeState(st) {
    const label = st.label;
    if (label === 'discovering') {
      // sheet 内已有 loading；顶条可选
      return;
    }
    if (label === 'connecting') {
      setBanner('正在投屏至 ' + (st.deviceName || '设备') + '…', true);
      return;
    }
    if (label === 'playing') {
      casting = casting || { id: '', name: st.deviceName || '设备' };
      casting.name = st.deviceName || casting.name;
      enterCastingUi();
      return;
    }
    if (label === 'failed') {
      // 失败由 play() callback 处理文案；此处若仍显示投屏中则清掉
      if (casting) clearCastingUi(true);
      return;
    }
    if (label === 'idle') {
      // 电视侧停 / Stop：≤12s 内离开「投屏中」
      if (casting) clearCastingUi(true);
    }
  }

  function showSheet() {
    const el = $('#cast-sheet');
    if (!el) return;
    el.classList.remove('hidden');
    el.setAttribute('aria-hidden', 'false');
    scanDevices();
  }
  function hideSheet() {
    const el = $('#cast-sheet');
    if (!el) return;
    el.classList.add('hidden');
    el.setAttribute('aria-hidden', 'true');
  }

  function setFail(title, sub, actions) {
    const box = $('#cast-fail');
    if (!box) return;
    box.classList.remove('hidden');
    box.innerHTML =
      `<div class="cast-fail-title">${esc(title)}</div>` +
      `<div class="cast-fail-sub">${esc(sub || '')}</div>` +
      `<div class="cast-fail-actions"></div>`;
    const row = box.querySelector('.cast-fail-actions');
    (actions || []).forEach((a) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = a.label;
      b.onclick = a.onClick;
      row.appendChild(b);
    });
  }
  function clearFail() {
    const box = $('#cast-fail');
    if (!box) return;
    box.classList.add('hidden');
    box.innerHTML = '';
  }

  function renderBody(mode) {
    const body = $('#cast-sheet-body');
    if (!body) return;
    if (mode === 'loading') {
      body.innerHTML =
        `<div class="cast-loading"><div class="cast-spinner"></div>` +
        `<div style="font-size:13px;color:var(--mut)">正在查找设备…</div></div>`;
      return;
    }
    if (mode === 'empty') {
      body.innerHTML =
        `<div class="cast-empty">` +
        `<div class="cast-empty-title">未找到可投屏设备</div>` +
        `<div class="cast-empty-sub">请确认电视/盒子与手机在同一 Wi‑Fi，并已开启投屏或 DLNA</div>` +
        `<button type="button" class="cast-primary-btn" id="cast-rescan">重新扫描</button></div>`;
      const btn = $('#cast-rescan');
      if (btn) btn.onclick = scanDevices;
      return;
    }
    body.innerHTML = '';
    devices.forEach((d) => {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'cast-device';
      const using = casting && casting.id === d.id;
      row.innerHTML =
        `<span class="cast-device-ico"></span>` +
        `<span class="cast-device-name">${esc(d.name || '设备')}</span>` +
        (using ? `<span class="cast-chip">使用中</span>` : `<span class="cast-chevron">›</span>`);
      row.onclick = () => selectDevice(d);
      body.appendChild(row);
    });
  }

  async function scanDevices() {
    clearFail();
    const refresh = $('#cast-refresh');
    if (refresh) refresh.disabled = true;
    renderBody('loading');
    if (!hasNative()) {
      renderBody('empty');
      setFail(
        '未找到可投屏设备',
        '请在 Android App 内使用投屏（浏览器不支持局域网 DLNA 发现）',
        [{ label: '重新扫描', onClick: scanDevices }],
      );
      if (refresh) refresh.disabled = false;
      return;
    }
    const r = await callNative('scan', []);
    if (refresh) refresh.disabled = false;
    devices = (r.ok && Array.isArray(r.data)) ? r.data : [];
    if (!devices.length) {
      renderBody('empty');
      setFail(
        '未找到可投屏设备',
        '请确认电视/盒子与手机在同一 Wi‑Fi，并已开启投屏或 DLNA',
        [{ label: '重新扫描', onClick: scanDevices }],
      );
      return;
    }
    clearFail();
    renderBody('list');
  }

  function currentPlayUrl() {
    try {
      if (typeof lineList !== 'undefined' && lineList && lineList.length) {
        const sel = $('#line-sel');
        const idx = sel ? +sel.value : 0;
        return (lineList[idx] && lineList[idx].url) || '';
      }
    } catch (_) {}
    return '';
  }
  function currentTitle() {
    const t = $('#p-title');
    return (t && t.textContent) || 'xx9';
  }

  async function selectDevice(d) {
    const url = currentPlayUrl();
    if (!url) {
      setFail('投屏失败', '当前没有可推送的播放地址', [
        { label: '关闭', onClick: clearFail },
      ]);
      return;
    }
    setBanner(`正在投屏至 ${d.name}…`, true);
    hideSheet();
    const r = await callNative('play', [d.id, url, currentTitle()]);
    if (!r.ok) {
      clearCastingUi(false);
      const hls = r.error === 'HLS_UNSUPPORTED';
      showSheet();
      setFail(
        hls ? '设备可能不支持此片源格式' : '投屏失败',
        hls
          ? '可改回本机播放，或换支持 HLS/m3u8 的设备'
          : '无法把当前片源推到该设备，请换一台或稍后再试',
        hls
          ? [
              { label: '本机播放', onClick: () => { clearFail(); hideSheet(); clearCastingUi(true); } },
              { label: '换设备', onClick: () => { clearFail(); scanDevices(); } },
            ]
          : [
              { label: '重试', onClick: () => selectDevice(d) },
              { label: '换设备', onClick: () => { clearFail(); scanDevices(); } },
            ],
      );
      return;
    }
    casting = { id: d.id, name: d.name };
    lineDirty = false;
    paused = false;
    pauseSupported = true;
    if (r.state) applyNativeState(r.state);
    else enterCastingUi();
  }

  function setBanner(text, connecting) {
    const b = $('#cast-banner');
    const tx = $('#cast-banner-text');
    if (!b || !tx) return;
    tx.textContent = text;
    b.classList.remove('hidden');
  }

  function enterCastingUi() {
    setBanner(`投屏至 ${casting.name}`, false);
    const btn = $('#cast-btn');
    if (btn) {
      btn.classList.add('casting');
      btn.innerHTML = '<span class="cast-dot"></span>已投屏';
    }
    const remote = $('#cast-remote');
    if (remote) remote.classList.remove('hidden');
    const sync = $('#sync-cast-btn');
    if (sync) sync.classList.toggle('hidden', !lineDirty);
    const box = document.querySelector('.player-box');
    if (box) {
      box.classList.add('cast-away');
      if (!$('#cast-away-msg')) {
        const m = document.createElement('div');
        m.id = 'cast-away-msg';
        m.className = 'cast-away-msg';
        m.textContent = '画面在电视上';
        box.style.position = box.style.position || 'relative';
        box.appendChild(m);
      }
    }
    try {
      const v = $('#video');
      if (v) { v.muted = true; v.pause(); }
    } catch (_) {}
    updatePauseBtn();
  }

  function clearCastingUi(resumeLocal) {
    casting = null;
    lineDirty = false;
    const b = $('#cast-banner');
    if (b) b.classList.add('hidden');
    const remote = $('#cast-remote');
    if (remote) remote.classList.add('hidden');
    const sync = $('#sync-cast-btn');
    if (sync) sync.classList.add('hidden');
    const btn = $('#cast-btn');
    if (btn) {
      btn.classList.remove('casting');
      btn.innerHTML = '<span class="cast-dot"></span>投屏';
    }
    const box = document.querySelector('.player-box');
    if (box) box.classList.remove('cast-away');
    const msg = $('#cast-away-msg');
    if (msg) msg.remove();
    if (resumeLocal) {
      try {
        const v = $('#video');
        if (v) { v.muted = false; v.play().catch(() => {}); }
      } catch (_) {}
    }
  }

  function updatePauseBtn() {
    const b = $('#cast-pause');
    const hint = $('#cast-remote-hint');
    if (!b) return;
    b.disabled = !pauseSupported;
    b.textContent = paused ? '继续' : '暂停';
    if (hint) {
      if (!pauseSupported) {
        hint.textContent = '此设备暂不支持遥控暂停';
        hint.classList.remove('hidden');
      } else hint.classList.add('hidden');
    }
  }

  async function stopCast() {
    if (hasNative()) await callNative('stop', []);
    clearCastingUi(true);
    hideSheet();
  }

  function onLineChanged() {
    if (!casting) return;
    lineDirty = true;
    const sync = $('#sync-cast-btn');
    if (sync) sync.classList.remove('hidden');
  }

  async function syncToTv() {
    if (!casting) return;
    const url = currentPlayUrl();
    const r = await callNative('play', [casting.id, url, currentTitle()]);
    if (r.ok) {
      lineDirty = false;
      const sync = $('#sync-cast-btn');
      if (sync) sync.classList.add('hidden');
      if (typeof toast === 'function') toast('已同步');
    } else if (typeof toast === 'function') {
      toast('同步失败，请重试或换设备');
    }
  }

  function esc(s) {
    return String(s || '').replace(/[&<>"]/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  function bind() {
    const castBtn = $('#cast-btn');
    if (castBtn) castBtn.onclick = () => showSheet();
    const close = $('#cast-sheet-close');
    if (close) close.onclick = hideSheet;
    const bd = $('#cast-sheet-backdrop');
    if (bd) bd.onclick = hideSheet;
    const refresh = $('#cast-refresh');
    if (refresh) refresh.onclick = scanDevices;
    const disc = $('#cast-disconnect');
    if (disc) disc.onclick = stopCast;
    const stop = $('#cast-stop');
    if (stop) stop.onclick = stopCast;
    const pause = $('#cast-pause');
    if (pause) {
      pause.onclick = async () => {
        if (!pauseSupported) return;
        const r = await callNative(paused ? 'resume' : 'pause', []);
        if (!r.ok && (r.error === 'UNSUPPORTED_PAUSE' || /pause|not implemented/i.test(r.error || ''))) {
          pauseSupported = false;
          updatePauseBtn();
          return;
        }
        if (r.ok) { paused = !paused; updatePauseBtn(); }
      };
    }
    const sync = $('#sync-cast-btn');
    if (sync) sync.onclick = syncToTv;

    // 钩子：线路切换后标记需同步
    const sel = $('#line-sel');
    if (sel) {
      const prev = sel.onchange;
      sel.addEventListener('change', () => onLineChanged());
    }
  }

  // 导出给 app.js：线路自动切换时也标记
  window.xx9CastOnLineChange = onLineChanged;
  window.xx9CastStopIfAny = () => { if (casting) stopCast(); };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else bind();
})();

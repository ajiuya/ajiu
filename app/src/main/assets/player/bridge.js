(function (g) {
  'use strict';
  const config = g.__AJIU_CONFIG__ || {};
  const base = new URL('.', g.location.href);
  const query = new URLSearchParams(g.location.search);
  const words = g.__AJIU_L10N__ || {};
  const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
  function t(key, values) {
    let text = own(words, key) ? String(words[key]) : key;
    Object.keys(values || {}).forEach(k => { text = text.split('{' + k + '}').join(String(values[k])); });
    return text;
  }
  document.documentElement.lang = words.locale || navigator.language || 'en';
  document.title = config.title || t('title');
  document.querySelectorAll('[data-l10n]').forEach(el => { el.textContent = t(el.dataset.l10n); });
  document.querySelectorAll('[data-l10n-label]').forEach(el => {
    el.setAttribute('aria-label', t(el.dataset.l10nLabel));
    el.title = t(el.dataset.l10nLabel);
  });
  document.querySelectorAll('[data-l10n-placeholder]').forEach(el => { el.placeholder = t(el.dataset.l10nPlaceholder); });
  function endpoint(path) {
    const u = new URL(path, base);
    if (u.origin !== base.origin || !u.pathname.startsWith(base.pathname)) throw Error('session-route');
    return u.href;
  }
  function session(u) { return u.origin === base.origin && u.pathname.startsWith(base.pathname); }
  function loopback(u) { return ['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname); }
  function unbox(value) {
    let s = String(value || '').trim();
    for (let i = 0; i < 8; i++) {
      let u;
      try { u = new URL(s, base); } catch (_) { return s; }
      if ((session(u) || loopback(u)) && /\/(relay|local)$/.test(u.pathname)) {
        const raw = u.searchParams.get(u.pathname.endsWith('/relay') ? 'u' : 'path');
        if (raw) { s = raw; continue; }
      }
      break;
    }
    return s;
  }
  function localPath(value) {
    let s = String(value || '');
    if (/^file:/i.test(s)) {
      try { s = decodeURIComponent(new URL(s).pathname).replace(/^\/~\//, '~/'); } catch (_) { return ''; }
    }
    return s;
  }
  function stable(value) {
    const s = localPath(unbox(value));
    if (!s || /^blob:/i.test(s)) return '';
    if (/^https?:/i.test(s)) {
      try { const u = new URL(s); return loopback(u) || session(u) || u.username || u.password ? '' : u.href; } catch (_) { return ''; }
    }
    if (/^[a-z][\w+.-]*:/i.test(s) || s.startsWith('//') || /[\u0000-\u001f\\]/.test(s)) return '';
    return s;
  }
  function mediaURL(value, referer) {
    let s = String(value || '').trim();
    if (/^blob:/i.test(s)) {
      if (new URL(s).origin !== base.origin) throw Error('blob-origin');
      return s;
    }
    const candidate = new URL(s, base);
    if (session(candidate) && /\/(local|relay)$|\/dash\/|\/files\//.test(candidate.pathname)) return candidate.href;
    s = stable(s);
    if (!s) throw Error('invalid-source');
    const u = new URL(/^https?:\/\//i.test(s) ? 'relay' : 'local', base);
    if (/^https?:\/\//i.test(s)) {
      u.searchParams.set('u', s);
      if (referer || config.referer) u.searchParams.set('ref', referer || config.referer);
    } else { u.searchParams.set('path', s); }
    return u.href;
  }
  function hash(s) {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(16);
  }
  const namespace = String(g.__AJIU_STATE_NAMESPACE__ || '');
  const epoch = Number(g.__AJIU_STATE_EPOCH__);
  const positive = v => Number.isSafeInteger(v) && v > 0;
  const revisionOK = r => r && positive(r.epoch) && positive(r.sequence);
  const prefix = 'ajiu_player_v3:' + namespace + ':';
  const stateReady = /^[a-f0-9]{64}$/.test(namespace) && positive(epoch);
  const validKey = k => stateReady && typeof k === 'string' && k.startsWith(prefix) && k.length <= 2048;
  function key(scope, id) { return stateReady ? prefix + scope + ':' + hash(String(id == null ? scope : id)) : ''; }
  let sequence = 0;
  const saved = Object.assign(Object.create(null), g.__AJIU_SAVED_STATE__ || {});
  const revisions = Object.assign(Object.create(null), g.__AJIU_STATE_REVISIONS__ || {});
  const recovered = new Set(), pending = new Map(), timers = new Map(), flights = new Map();
  const compare = (a, b) => a.epoch - b.epoch || a.sequence - b.sequence;
  function storageFailure() {
    diagnostic('state-error');
    const el = document.getElementById('ajiuStatus');
    if (el) { el.textContent = t('storage_failed'); el.dataset.state = 'error'; }
  }
  function schedule(k) {
    if (!timers.has(k)) timers.set(k, setTimeout(() => { timers.delete(k); persist(k, false); }, 1000));
  }
  function recover(k) {
    if (!validKey(k) || recovered.has(k)) return;
    recovered.add(k);
    try {
      const entry = JSON.parse(localStorage.getItem(k));
      const server = revisionOK(revisions[k]) ? revisions[k] : { epoch: 0, sequence: 0 };
      if (entry && entry.format === 'ajiu-state-v3' && (typeof entry.value === 'string' || entry.value === null) && revisionOK(entry.revision) && entry.revision.epoch <= epoch && compare(entry.revision, server) > 0) {
        if (entry.revision.epoch === epoch) sequence = Math.max(sequence, entry.revision.sequence);
        if (entry.value === null) delete saved[k]; else saved[k] = entry.value;
        pending.set(k, entry); schedule(k);
      }
    } catch (_) {}
  }
  function read(k) {
    if (!validKey(k)) return null;
    recover(k);
    try { return own(saved, k) ? JSON.parse(saved[k]) : null; } catch (_) { return null; }
  }
  function serialize(value) {
    try {
      const text = JSON.stringify(value);
      if (typeof text !== 'string' || new Blob([text]).size > 512 * 1024 || /(?:https?:\/\/(?:127\.0\.0\.1|localhost):\d+)?\/[a-f0-9]{64}\//i.test(text.replace(/\\\//g, '/'))) throw Error('state-size-or-source');
      return text;
    } catch (_) { storageFailure(); return null; }
  }
  function write(k, value) {
    if (!validKey(k)) { storageFailure(); return false; }
    recover(k);
    const text = value === null ? null : serialize(value);
    if (text === null && value !== null) return false;
    if ((text === null && !own(saved, k)) || saved[k] === text) { if (pending.has(k)) schedule(k); return true; }
    if (sequence >= Number.MAX_SAFE_INTEGER) { storageFailure(); return false; }
    const entry = { format: 'ajiu-state-v3', value: text, revision: { epoch: epoch, sequence: ++sequence } };
    if (text === null) delete saved[k]; else saved[k] = text;
    pending.set(k, entry);
    try { localStorage.setItem(k, JSON.stringify(entry)); } catch (_) {}
    schedule(k); return true;
  }
  function persist(k, beacon) {
    clearTimeout(timers.get(k)); timers.delete(k);
    const entry = pending.get(k);
    if (!entry) return;
    const body = JSON.stringify({ key: k, value: entry.value, revision: entry.revision });
    // A queued beacon is not a durability acknowledgement; keep the local outbox until POST succeeds.
    if (beacon && navigator.sendBeacon) {
      try { if (navigator.sendBeacon(endpoint('api/state'), new Blob([body], { type: 'application/json' }))) { schedule(k); return; } } catch (_) {}
    }
    const id = k + ':' + entry.revision.epoch + ':' + entry.revision.sequence;
    if (flights.has(id)) return;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    flights.set(id, controller);
    fetch(endpoint('api/state'), { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: body, keepalive: !!beacon, signal: controller.signal })
      .then(r => {
        if (!r.ok) throw Error('state-http');
        if (!revisionOK(revisions[k]) || compare(entry.revision, revisions[k]) >= 0) revisions[k] = entry.revision;
        if (pending.get(k) === entry) pending.delete(k);
      }).catch(storageFailure).then(() => {
        clearTimeout(timeout); flights.delete(id);
        if (pending.has(k)) schedule(k);
      });
  }
  function flushState() { pending.forEach((_, k) => persist(k, true)); }
  function post(type, payload) {
    const bridge = g.AjiuNative;
    if (!bridge || typeof bridge.postMessage !== 'function') return false;
    try { bridge.postMessage(JSON.stringify({ type: type, payload: payload || {} })); return true; } catch (_) { return false; }
  }
  const logs = [];
  function diagnostic(code, mode) {
    const item = {
      code: /^(attach|ready|cancel|detach|fatal|recover|script-timeout|script-error|media-error|destroy|request-error|state-error)$/.test(code) ? code : 'media-error',
      mode: /^(hls|dash|direct|native-hls|player)$/.test(mode) ? mode : 'player', at: Date.now()
    };
    logs.push(item); if (logs.length > 80) logs.shift(); post('diagnostic', item);
  }
  function name(value) {
    const last = unbox(value).split(/[?#]/)[0].split('/').pop();
    try { return last ? decodeURIComponent(last) : t('title'); } catch (_) { return last || t('title'); }
  }
  function fullscreen(el) {
    const current = document.fullscreenElement || document.webkitFullscreenElement;
    const exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (current && exit) return Promise.resolve(exit.call(document)).catch(() => {});
    const request = el.requestFullscreen || el.webkitRequestFullscreen;
    if (request) {
      try { return Promise.resolve(request.call(el)).catch(() => { post('fullscreen', { enabled: true }); }); } catch (_) {}
    }
    post('fullscreen', { enabled: true }); return Promise.resolve();
  }
  function pip(video) {
    if (post('pip', { enabled: true })) return Promise.resolve();
    if (document.pictureInPictureElement && document.exitPictureInPicture) return document.exitPictureInPicture().catch(() => {});
    if (video.requestPictureInPicture) return video.requestPictureInPicture().catch(() => {});
    return Promise.resolve();
  }
  let handoff = null;
  if (query.get('handoff') === '1') {
    try {
      handoff = JSON.parse(sessionStorage.getItem('ajiu.native.handoff'));
      sessionStorage.removeItem('ajiu.native.handoff');
      if (!handoff || !Array.isArray(handoff.items)) handoff = null;
    } catch (_) {}
  }
  const explicitPosition = handoff && Number.isFinite(handoff.position) ? handoff.position : query.has('position') && Number.isFinite(Number(query.get('position'))) ? Number(query.get('position')) : null;
  let current = null;
  function bind(video, hooks) {
    hooks = hooks || {};
    if (current) current.destroy();
    let destroyed = false, source = '', title = '', progressKey = '', grouped = false, restoring = false, positionConsumed = false, lastState = 0, lastSave = 0, lastItems = null;
    let itemSource = null, itemRevision = null, itemCache = [];
    const listeners = [], cleanups = [];
    const status = document.getElementById('ajiuStatus');
    function snapshot() {
      const data = hooks.snapshot ? hooks.snapshot() : {};
      const items = data.items || [];
      if (items !== itemSource || data.revision !== itemRevision) {
        itemSource = items; itemRevision = data.revision;
        itemCache = items.map(it => ({ name: it.name || name(it.url), url: stable(it.url), kind: it.kind || it.type || 'video', mime: it.mime || it.mediaType || '', identity: it.identity || '', referer: it.referer || '', needsFile: !!it.needsFile || /^blob:/i.test(it.url || '') })).filter(it => it.url || it.needsFile);
      }
      return { src: stable(data.src || source), title: data.title || title || config.title || t('title'), items: itemCache, index: Math.max(0, data.index || 0), position: Number.isFinite(video.currentTime) ? video.currentTime : 0, identity: data.identity || '', referer: data.referer || '', rate: video.playbackRate || 1 };
    }
    function state() {
      if (destroyed) return;
      const data = snapshot();
      const payload = { playing: !video.paused && !video.ended, title: data.title, src: data.src, index: data.index, position: data.position, duration: Number.isFinite(video.duration) ? video.duration : 0, readyState: video.readyState || 0, hasVideo: video.videoWidth > 0, videoWidth: video.videoWidth || 0, videoHeight: video.videoHeight || 0 };
      if (data.items !== lastItems) payload.items = data.items;
      if (post('state', payload)) lastItems = data.items;
      if (navigator.mediaSession) {
        try {
          navigator.mediaSession.playbackState = video.paused ? 'paused' : 'playing';
          if (Number.isFinite(video.duration) && video.duration > 0 && navigator.mediaSession.setPositionState) navigator.mediaSession.setPositionState({ duration: video.duration, position: Math.min(video.currentTime, video.duration), playbackRate: video.playbackRate || 1 });
        } catch (_) {}
      }
    }
    function save() {
      if (!progressKey || restoring || video.readyState === 0 || !Number.isFinite(video.currentTime)) return;
      const time = video.ended ? 0 : video.currentTime;
      if (grouped) {
        const data = read(progressKey) || { positions: {} };
        if (!data.positions) data.positions = {};
        data.positions[hash(source)] = { source: source, time: time };
        write(progressKey, data);
      } else { write(progressKey, { time: time }); }
    }
    function on(event, fn) { video.addEventListener(event, fn); listeners.push([event, fn]); }
    function show(kind) {
      if (status) { status.textContent = t(kind === 'error' ? 'media_failed' : kind); status.dataset.state = kind; }
    }
    function play() {
      if (destroyed) return;
      if (hooks.play) { hooks.play(); return; }
      const promise = video.play(); if (promise && promise.catch) promise.catch(() => show('ready'));
    }
    function command(action, value) {
      if (destroyed) return;
      if (action === 'play') { controller.suspended = false; play(); }
      else if (action === 'pause') { controller.suspended = true; video.pause(); }
      else if (action === 'next' && hooks.next) { controller.suspended = false; hooks.next(); }
      else if (action === 'previous' && hooks.previous) { controller.suspended = false; hooks.previous(); }
      else if (action === 'seek' && Number.isFinite(Number(value))) { try { video.currentTime = Math.max(0, Math.min(Number(value), Number.isFinite(video.duration) ? video.duration : Number(value))); } catch (_) {} }
      else if (action === 'fullpage') {
        try { save(); flushState(); sessionStorage.setItem('ajiu.native.handoff', JSON.stringify(controller.snapshot())); g.location.href = endpoint('index.html?handoff=1'); } catch (_) { storageFailure(); }
      }
    }
    const controller = {
      suspended: false, pause: () => command('pause'), resume: () => command('play'), command: command,
      source(raw, label, identity) {
        save(); source = stable(raw); title = label || name(raw);
        progressKey = source ? key('progress', identity || source) : ''; grouped = !!identity; restoring = true;
        show('loading');
        if (navigator.mediaSession && g.MediaMetadata) { try { navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: t('title') }); } catch (_) {} }
        state();
      },
      status: show, snapshot: () => { const data = snapshot(); data.items = data.items.map(it => Object.assign({}, it)); return data; },
      destroy() {
        if (destroyed) return;
        save(); video.pause(); state(); destroyed = true;
        listeners.forEach(it => video.removeEventListener(it[0], it[1])); cleanups.forEach(fn => fn());
        if (hooks.destroy) hooks.destroy();
        if (g.NetMedia) g.NetMedia.detach(video);
        if (navigator.mediaSession) ['play', 'pause', 'nexttrack', 'previoustrack', 'seekto', 'seekbackward', 'seekforward'].forEach(a => { try { navigator.mediaSession.setActionHandler(a, null); } catch (_) {} });
        flushState(); diagnostic('destroy');
      }
    };
    on('timeupdate', () => {
      if (Date.now() - lastSave >= 1000) { lastSave = Date.now(); save(); }
      if (Date.now() - lastState >= 1000) { lastState = Date.now(); state(); }
    });
    ['play', 'pause', 'ended', 'seeked', 'durationchange', 'ratechange'].forEach(event => on(event, () => { save(); state(); if (event === 'pause' || event === 'ended') flushState(); }));
    on('loadedmetadata', () => {
      let savedProgress = progressKey ? read(progressKey) : null;
      if (grouped) { savedProgress = savedProgress && savedProgress.positions && savedProgress.positions[hash(source)]; if (savedProgress && savedProgress.source !== source) savedProgress = null; }
      if (explicitPosition !== null && !positionConsumed) {
        try { video.currentTime = Math.max(0, Math.min(explicitPosition, Number.isFinite(video.duration) ? video.duration : explicitPosition)); positionConsumed = true; } catch (_) {}
      } else if (savedProgress && savedProgress.time > 0 && Number.isFinite(video.duration) && savedProgress.time < video.duration - 2) {
        try { video.currentTime = savedProgress.time; } catch (_) {}
      }
      restoring = false; show('ready'); state();
    });
    ['playing', 'pause', 'waiting', 'error'].forEach(event => on(event, () => { show({ playing: 'playing', pause: 'paused', waiting: 'loading', error: 'error' }[event]); if (event === 'error') diagnostic('media-error'); }));
    let hiddenPlaying = false;
    function visibility() { if (document.hidden) { hiddenPlaying = !video.paused && !video.ended; save(); flushState(); } else if (hiddenPlaying && !controller.suspended) play(); }
    document.addEventListener('visibilitychange', visibility); cleanups.push(() => document.removeEventListener('visibilitychange', visibility));
    if (navigator.mediaSession) {
      const actions = { play: controller.resume, pause: controller.pause, nexttrack: () => command('next'), previoustrack: () => command('previous'), seekto: d => command('seek', d.seekTime), seekbackward: d => command('seek', video.currentTime - (d.seekOffset || 10)), seekforward: d => command('seek', video.currentTime + (d.seekOffset || 10)) };
      Object.keys(actions).forEach(a => { try { navigator.mediaSession.setActionHandler(a, actions[a]); } catch (_) {} });
    }
    g.AjiuPlayer = controller; current = controller; show('choose_media'); return controller;
  }
  function request(path, params, signal) {
    const u = new URL(endpoint(path));
    Object.keys(params || {}).forEach(k => u.searchParams.set(k, params[k]));
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal) { if (signal.aborted) controller.abort(); else signal.addEventListener('abort', abort, { once: true }); }
    const timer = setTimeout(abort, 30000);
    return fetch(u.href, { signal: controller.signal, credentials: 'same-origin' }).then(r => {
      if (!r.ok) { const error = Error('request-http'); error.status = r.status; throw error; }
      if (Number(r.headers.get('Content-Length')) > 4 * 1024 * 1024) throw Error('request-size');
      return r.text();
    }).then(text => { if (text.length > 4 * 1024 * 1024) throw Error('request-size'); return JSON.parse(text); }).catch(error => { if (error.name !== 'AbortError') diagnostic('request-error'); throw error; }).finally(() => { clearTimeout(timer); if (signal) signal.removeEventListener('abort', abort); });
  }
  try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k && k.startsWith(prefix)) recover(k); } } catch (_) {}
  g.addEventListener('pagehide', () => { if (current) current.destroy(); flushState(); });
  g.AjiuBridge = { config: config, handoff: handoff, base: base.href, url: mediaURL, original: unbox, stable: stable, localPath: localPath, endpoint: endpoint, t: t, key: key, read: read, write: write, flushState: flushState, name: name, post: post, diagnostic: diagnostic, logs: () => logs.slice(), bind: bind, fullscreen: fullscreen, pip: pip, request: request };
})(window);

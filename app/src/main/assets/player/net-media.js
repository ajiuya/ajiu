(function (g) {
  'use strict';
  const B = g.AjiuBridge, scripts = new Map();
  let serial = 0;
  const real = u => B.original(u);
  const isHls = u => /\.m3u8?(?:[?#]|$)/i.test(real(u));
  const isDash = u => /\.mpd(?:[?#]|$)/i.test(real(u));
  const log = (code, mode) => B.diagnostic(code, mode);
  function ensure(kind, owner) {
    const present = kind === 'hls' ? g.Hls : g.dashjs;
    if (present) return Promise.resolve(present);
    let entry = scripts.get(kind);
    if (!entry) {
      entry = { users: 0, public: false, done: false };
      scripts.set(kind, entry);
      entry.promise = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        let timer;
        function finish(error) {
          if (entry.done) return;
          entry.done = true; clearTimeout(timer); script.onload = script.onerror = null;
          const lib = kind === 'hls' ? g.Hls : g.dashjs;
          if (error || !lib) {
            script.remove(); if (scripts.get(kind) === entry) scripts.delete(kind);
            log(error === 'cancel' ? 'cancel' : error === 'timeout' ? 'script-timeout' : 'script-error', kind);
            reject(Error('engine-load-failed'));
          } else { resolve(lib); }
        }
        entry.cancel = () => finish('cancel'); script.async = true;
        script.src = B.endpoint(kind === 'hls' ? 'vendor/hls-1.5.15.min.js' : 'vendor/dash-4.7.4.all.min.js');
        script.onload = () => finish(); script.onerror = () => finish('error');
        timer = setTimeout(() => finish('timeout'), 8000); document.head.appendChild(script);
      });
      entry.promise.catch(() => {});
    }
    if (owner) {
      entry.users++; let released = false;
      owner.cleanups.push(() => { if (released) return; released = true; entry.users--; if (!entry.users && !entry.public && !entry.done) entry.cancel(); });
    } else { entry.public = true; }
    return entry.promise;
  }
  function detach(video) {
    const s = video.__net;
    if (s) {
      video.__net = null; s.cancelled = true;
      s.timers.forEach(clearTimeout); s.timers = [];
      if (s.abort) s.abort.abort();
      s.cleanups.forEach(fn => { try { fn(); } catch (_) {} }); s.cleanups = [];
      if (s.hls) { try { s.hls.destroy(); } catch (_) {} }
      if (s.dash) { try { s.dash.reset(); } catch (_) {} }
      s.resolve({ mode: 'cancelled', cancelled: true, generation: s.id }); log('detach', s.mode);
    }
    video.__hls = null; video.__dash = null;
    try { video.pause(); video.removeAttribute('src'); video.load(); } catch (_) {}
  }
  function attach(video, raw, opts) {
    opts = opts || {}; detach(video);
    const s = { id: ++serial, cancelled: false, timers: [], cleanups: [], hls: null, dash: null, mode: 'direct', resolve: null };
    video.__net = s;
    const promise = new Promise(resolve => { s.resolve = resolve; });
    const active = () => video.__net === s && !s.cancelled;
    function later(fn, ms) {
      const timer = setTimeout(() => { s.timers = s.timers.filter(t => t !== timer); if (active()) fn(); }, ms);
      s.timers.push(timer); return timer;
    }
    function result(mode, error) { if (active()) s.resolve({ mode: mode, error: error || null, generation: s.id, hls: s.hls, dash: s.dash, cancelled: false }); }
    function notify(state) {
      if (!active()) return;
      if (opts.onStatus) opts.onStatus(state);
      video.dispatchEvent(new CustomEvent('netmedia-status', { detail: { state: state, mode: s.mode } }));
    }
    function fatal() {
      if (!active() || s.failed) return;
      s.failed = true; s.timers.forEach(clearTimeout); s.timers = [];
      if (s.abort) s.abort.abort();
      if (s.hls) { try { s.hls.destroy(); } catch (_) {} s.hls = null; video.__hls = null; }
      if (s.dash) { try { s.dash.reset(); } catch (_) {} s.dash = null; video.__dash = null; }
      s.cleanups.forEach(fn => { try { fn(); } catch (_) {} }); s.cleanups = [];
      try { video.pause(); video.removeAttribute('src'); video.load(); } catch (_) {}
      log('fatal', s.mode); notify('error'); result('error', 'media-load-failed');
    }
    let url;
    try { url = B.url(raw, opts.referer); } catch (_) { fatal(); return promise; }
    const type = String(opts.type || opts.mime || '').toLowerCase();
    const hls = isHls(raw) || /hls|mpegurl/.test(type), dash = isDash(raw) || /dash|mpd/.test(type);
    s.mode = hls ? 'hls' : dash ? 'dash' : 'direct'; log('attach', s.mode); notify('loading');
    later(fatal, 25000);
    function ready() {
      if (!active() || s.failed) return;
      s.timers.forEach(clearTimeout); s.timers = [];
      log('ready', s.mode); notify('ready'); result(s.mode);
    }
    function listen(event, fn) { video.addEventListener(event, fn); s.cleanups.push(() => video.removeEventListener(event, fn)); }
    listen('loadedmetadata', ready); listen('error', fatal);
    if (hls) {
      ensure('hls', s).then(Hls => {
        if (!active() || s.failed) return;
        if (!Hls.isSupported()) {
          if (video.canPlayType('application/vnd.apple.mpegurl')) { s.mode = 'native-hls'; video.src = url; result('native-hls'); } else { fatal(); }
          return;
        }
        const DefaultLoader = Hls.DefaultConfig.loader;
        function RelayLoader(settings) {
          const loader = new DefaultLoader(settings), load = loader.load;
          loader.load = function (context, conf, callbacks) {
            const wrapped = Object.assign({}, callbacks);
            wrapped.onSuccess = function (response, stats, ctx, network) {
              if (!active() || s.failed) return;
              // Server manifests already contain session routes, including local relative segments.
              response.url = network && network.responseURL || context.url;
              callbacks.onSuccess(response, stats, ctx, network);
            };
            try { context.url = B.url(context.url, opts.referer); } catch (_) { fatal(); return; }
            return load.call(loader, context, conf, wrapped);
          };
          return loader;
        }
        const instance = new Hls({ loader: RelayLoader, enableWorker: true, lowLatencyMode: false, backBufferLength: 60, maxBufferLength: opts.maxBuffer || 30, maxMaxBufferLength: 60, manifestLoadingTimeOut: 15000, manifestLoadingMaxRetry: 2, fragLoadingTimeOut: 20000, fragLoadingMaxRetry: 2, startLevel: -1 });
        s.hls = instance; video.__hls = instance;
        let networkBudget = 2, mediaBudget = 2;
        instance.on(Hls.Events.MANIFEST_PARSED, ready);
        instance.on(Hls.Events.ERROR, (_, data) => {
          if (!active() || s.failed || !data.fatal) return;
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR && networkBudget > 0) { const attempt = 3 - networkBudget--; log('recover', 'hls'); later(() => instance.startLoad(), attempt * 500); }
          else if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaBudget > 0) { mediaBudget--; log('recover', 'hls'); later(() => instance.recoverMediaError(), 500); }
          else { fatal(); }
        });
        instance.attachMedia(video); instance.loadSource(url);
      }).catch(fatal);
    } else if (dash) {
      ensure('dash', s).then(lib => {
        if (!active() || s.failed) return;
        const instance = lib.MediaPlayer().create(); s.dash = instance; video.__dash = instance;
        instance.extend('RequestModifier', function () {
          return { modifyRequestURL: u => B.url(u, opts.referer), modifyRequestHeader: xhr => xhr };
        }, true);
        instance.updateSettings({ debug: { logLevel: 0 }, streaming: { retryAttempts: { MPD: 2, MediaSegment: 2, InitializationSegment: 2 }, buffer: { stableBufferTime: opts.maxBuffer || 30 } } });
        const events = lib.MediaPlayer.events;
        instance.on(events.STREAM_INITIALIZED, ready);
        instance.on(events.ERROR, fatal);
        // Keep the rewritten MPD URL as its base so server-owned DASH templates stay same-origin.
        instance.initialize(video, url, false);
      }).catch(fatal);
    } else { video.src = url; result('direct'); }
    return promise;
  }
  g.NetMedia = { attach: attach, detach: detach, isHls: isHls, isDash: isDash, isMp4: u => /\.(mp4|m4v|webm|ogg|ogv|mov|ts)(?:[?#]|$)/i.test(real(u)), isRemote: u => /^https?:/i.test(real(u)), isLocal: u => !/^https?:/i.test(real(u)), ensureHls: () => ensure('hls'), ensureDash: () => ensure('dash'), get ready() { return !!g.Hls; } };
})(window);

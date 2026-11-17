(function (g) {
  'use strict';
  const B = g.AjiuBridge, cfg = B.config, N = g.NetMedia, t = B.t;
  const $ = id => document.getElementById(id);
  const video = $('video'), stage = $('stage'), canvas = $('viz'), cover = $('cover');
  const q = new URLSearchParams(location.search), pageMode = document.body.dataset.mode;
  const PER = 30, LIST_PER = 100, rates = [1, 1.25, 1.5, 1.75, 2, 0.75, 0.5];
  const queueKey = B.key('tracks'), prefsKey = B.key('preferences');
  const S = { tracks: [], index: -1, revision: 0, mode: 0, rate: 1, listPage: 0, epPage: 0, detail: null, line: 0, identity: '', seeking: false };
  let destroyed = false, generation = 0, errorTimer = 0, errorCount = 0, failedGeneration = -1, dblTimer = 0, lastVol = 1, dragDepth = 0, catalogueGeneration = 0, catalogController = null, libraryController = null, hiddenPlaying = false;
  const cleanup = [];
  function node(tag, text, cls) { const el = document.createElement(tag); if (text != null) el.textContent = String(text); if (cls) el.className = cls; return el; }
  function button(text, fn, cls) { const el = node('button', text, cls); el.type = 'button'; el.onclick = fn; return el; }
  function listen(target, event, fn, options) { target.addEventListener(event, fn, options); cleanup.push(() => target.removeEventListener(event, fn, options)); }
  function format(time) {
    time = Math.max(0, Math.floor(Number.isFinite(time) ? time : 0));
    const h = Math.floor(time / 3600), m = Math.floor(time % 3600 / 60), s = time % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0');
  }
  function kind(url) { return /\.(mp4|webm|mkv|mov|m4v|ts|m2ts|ogv|avi|m3u8?|mpd|flv|3gp)(?:[?#]|$)/i.test(B.original(url)) ? 'video' : 'audio'; }
  function normalizeTrack(item) {
    if (!item || typeof item !== 'object') return null;
    const raw = String(item.url || item.u || ''), blob = /^blob:/i.test(raw);
    const url = blob ? raw : B.stable(raw);
    if (!url && !item.needsFile) return null;
    return { name: String(item.name || item.n || B.name(raw)), url: url, type: /^(audio|video)$/.test(item.kind || item.type) ? item.kind || item.type : kind(raw), mime: item.mime || item.mediaType || '', identity: item.identity || '', referer: item.referer || '', needsFile: !!item.needsFile, revoke: !!item.revoke };
  }
  function persistedTracks() { return S.tracks.map(it => ({ name: it.name, url: B.stable(it.url), kind: it.type, mime: it.mime, identity: it.identity, referer: it.referer, needsFile: it.needsFile || !!it.revoke || /^blob:/i.test(it.url) })).filter(it => it.url || it.needsFile); }
  function saveTracks() { return B.write(queueKey, persistedTracks()); }
  function savePreferences() { B.write(prefsKey, { mode: S.mode, rate: S.rate, volume: video.volume, muted: video.muted }); }
  function workIdentity(detail) {
    const source = detail.source === 'custom' ? B.stable(detail.api || cfg.api) : String(detail.source || '');
    return source && detail.id != null ? 'video:' + source + '|' + detail.id : 'series:' + B.stable(detail.api || cfg.api || (detail.eps && detail.eps[0] && detail.eps[0].u)) + '|' + String(detail.id || detail.title || cfg.query || '');
  }
  function normalizeDetail(data) {
    const d = Object.assign({}, data || {});
    d.title = String(d.title || d.name || cfg.title || cfg.query || t('title'));
    d.lines = (Array.isArray(d.lines) ? d.lines : []).map((line, i) => ({ id: String(line.id == null ? i : line.id), name: String(line.name || t('line', { number: i + 1 })), eps: (Array.isArray(line.eps) ? line.eps : []).filter(ep => ep && B.stable(ep.u)).map((ep, j) => ({ n: String(ep.n || j + 1), u: B.stable(ep.u), kind: ep.kind || 'video', mime: ep.mime || '' })) }));
    if (!d.lines.length && Array.isArray(d.eps)) d.lines = [{ id: '0', name: t('line', { number: 1 }), eps: d.eps.filter(ep => ep && B.stable(ep.u)).map((ep, i) => ({ n: String(ep.n || i + 1), u: B.stable(ep.u), kind: ep.kind || 'video', mime: ep.mime || '' })) }];
    return d;
  }
  function savePointer() {
    const it = S.tracks[S.index];
    if (!it || !it.identity) return;
    const url = B.stable(it.url);
    B.write(B.key('series', it.identity), { cur: S.index, url: url });
    const choiceKey = B.key('video-choice', it.identity), previous = B.read(choiceKey) || {};
    B.write(choiceKey, Object.assign({}, previous, { source: S.detail ? S.detail.source || '' : previous.source || '', id: S.detail ? String(S.detail.id || '') : previous.id || '', line: S.detail && S.detail.lines[S.line] ? S.detail.lines[S.line].id : previous.line || '0', index: S.index, url: url }));
  }
  const host = B.bind(video, {
    next: () => next(false), previous: previous, play: () => safePlay(true),
    snapshot: () => ({ src: S.tracks[S.index] ? S.tracks[S.index].url : '', title: S.tracks[S.index] ? S.tracks[S.index].name : cfg.title || '', items: S.tracks, index: S.index, revision: S.revision, identity: S.tracks[S.index] ? S.tracks[S.index].identity : '', referer: S.tracks[S.index] ? S.tracks[S.index].referer : '' }),
    destroy: () => {
      savePointer(); destroyed = true; ++generation; clearTimeout(errorTimer); clearTimeout(dblTimer);
      if (catalogController) catalogController.abort(); if (libraryController) libraryController.abort();
      cleanup.forEach(fn => fn()); stopViz();
      if (audioContext) audioContext.close().catch(() => {});
      S.tracks.filter(it => it.revoke).forEach(it => URL.revokeObjectURL(it.url));
    }
  });
  function pager(el, page, total, per, change) {
    el.textContent = '';
    const count = Math.max(1, Math.ceil(total / per));
    const prev = button(t('previous_page'), () => change(page - 1)), nextButton = button(t('next_page'), () => change(page + 1));
    prev.disabled = page <= 0; nextButton.disabled = page >= count - 1;
    const select = node('select'); select.setAttribute('aria-label', t('page_select'));
    for (let i = 0; i < count; i++) { const option = node('option', total ? (i * per + 1) + '–' + Math.min((i + 1) * per, total) : '0'); option.value = i; select.append(option); }
    select.value = page; select.onchange = () => change(Number(select.value));
    el.append(prev, select, node('span', t('of', { page: page + 1, total: total })), nextButton);
  }
  function renderList() {
    $('cnt').textContent = S.tracks.length;
    if ($('list').hidden) return;
    S.listPage = Math.max(0, Math.min(S.listPage, Math.ceil(S.tracks.length / LIST_PER) - 1));
    const items = $('items'); items.textContent = '';
    S.tracks.slice(S.listPage * LIST_PER, (S.listPage + 1) * LIST_PER).forEach((item, j) => {
      const i = S.listPage * LIST_PER + j, row = node('div', null, 'item' + (i === S.index ? ' active' : ''));
      const play = button('', () => { host.suspended = false; playIndex(i); }, 'item-play');
      play.append(node('span', String(i + 1).padStart(2, '0'), 'idx'), node('span', item.name, 'nm'), node('span', t(item.needsFile ? 'reselect' : item.type), 'badge'));
      play.setAttribute('aria-current', i === S.index ? 'true' : 'false');
      const remove = button('×', () => removeTrack(i), 'del'); remove.setAttribute('aria-label', t('remove') + ' ' + item.name);
      row.append(play, remove); items.append(row);
    });
    if (!S.tracks.length) items.append(node('p', t('empty')));
    pager($('listPages'), S.listPage, S.tracks.length, LIST_PER, page => { S.listPage = page; renderList(); });
  }
  function renderEpisodes() {
    if ($('episodes').hidden) return;
    const eps = S.tracks.map(it => ({ n: it.episode || it.name }));
    S.epPage = Math.max(0, Math.min(S.epPage, Math.ceil(eps.length / PER) - 1));
    $('epsLab').textContent = t('episode_count', { total: eps.length }); $('grid').textContent = '';
    pager($('episodePages'), S.epPage, eps.length, PER, page => { S.epPage = page; renderEpisodes(); });
    eps.slice(S.epPage * PER, (S.epPage + 1) * PER).forEach((ep, j) => {
      const i = S.epPage * PER + j, b = button(ep.n, () => { host.suspended = false; playIndex(i); }, 'episode' + (i === S.index ? ' active' : ''));
      b.dataset.episode = i; b.setAttribute('aria-current', i === S.index ? 'true' : 'false'); $('grid').append(b);
    });
    if (!eps.length) $('grid').append(node('p', t('no_episodes')));
  }
  function renderLines() {
    $('videoLines').hidden = !S.detail || !S.detail.lines.length;
    $('lineSelect').textContent = '';
    if (S.detail) S.detail.lines.forEach((line, i) => { const option = node('option', line.name + ' · ' + line.eps.length); option.value = i; $('lineSelect').append(option); });
    $('lineSelect').value = S.line;
  }
  function showInfo() {
    const d = S.detail || {}, rows = [d.title || cfg.title || t('title')];
    [['sourceName', 'source'], ['remark', 'updated'], ['year', 'year'], ['area', 'area'], ['director', 'director'], ['actor', 'cast'], ['content', 'info']].forEach(pair => { if (d[pair[0]]) rows.push(t(pair[1]) + ': ' + String(d[pair[0]])); });
    $('meta').textContent = rows.join('\n');
  }
  function replaceTracks(items) {
    S.tracks.filter(it => it.revoke).forEach(it => URL.revokeObjectURL(it.url));
    S.tracks = items; S.index = -1; S.revision++; S.listPage = 0; S.epPage = 0;
    renderList(); renderEpisodes(); saveTracks();
  }
  function adoptCatalog(data, selectedLine, selectedIndex) {
    if (destroyed) return;
    savePointer();
    const detail = normalizeDetail(data), identity = workIdentity(detail), choice = B.read(B.key('video-choice', identity)), pointer = B.read(B.key('series', identity));
    const lineId = selectedLine != null ? String(selectedLine) : choice && choice.line;
    let line = detail.lines.findIndex(it => it.id === lineId);
    if (line < 0) line = Number(detail.defaultLine) || 0;
    if (!detail.lines[line]) line = 0;
    S.detail = detail; S.line = line; S.identity = identity;
    const eps = detail.lines[line] ? detail.lines[line].eps : [];
    replaceTracks(eps.map(ep => Object.assign(normalizeTrack({ name: detail.title + ' · ' + ep.n, url: ep.u, kind: ep.kind, mime: ep.mime, identity: identity, referer: detail.referer || cfg.referer || '' }), { episode: ep.n })));
    let at = pointer && pointer.url ? S.tracks.findIndex(it => B.stable(it.url) === pointer.url) : -1;
    if (at < 0 && choice && choice.line === (detail.lines[line] && detail.lines[line].id) && choice.url) at = S.tracks.findIndex(it => B.stable(it.url) === choice.url);
    if (at < 0 && pointer && Number.isInteger(pointer.cur) && pointer.cur < eps.length) at = Math.max(0, pointer.cur);
    if (Number.isInteger(selectedIndex) && selectedIndex >= 0 && selectedIndex < eps.length) at = selectedIndex;
    $('episodes').hidden = false; $('btnInfo').hidden = false; renderLines(); showInfo(); renderEpisodes();
    document.title = detail.title; $('workTitle').textContent = detail.title;
    if (S.tracks.length) playIndex(Math.max(0, at)); else resetStage('no_episodes');
    if (Array.isArray(detail.errors) && detail.errors.length) $('catalogOutput').textContent = t('search_partial');
  }
  function addTracks(items, autoplay, automatic) {
    let normalized = items.map(normalizeTrack).filter(Boolean);
    if (automatic) { const have = new Set(S.tracks.map(it => B.stable(it.url) || (it.needsFile ? 'file:' + it.name : '')).filter(Boolean)); normalized = normalized.filter(it => { const id = B.stable(it.url) || (it.needsFile ? 'file:' + it.name : ''); if (!id || have.has(id)) return false; have.add(id); return true; }); }
    if (!normalized.length) return;
    const first = S.tracks.length; S.tracks.push(...normalized); S.revision++;
    renderList(); renderEpisodes(); saveTracks();
    if (autoplay) { host.suspended = false; playIndex(first); }
  }
  function removeTrack(i) {
    if (!S.tracks[i]) return;
    const current = i === S.index, gone = S.tracks.splice(i, 1)[0]; S.revision++;
    if (gone.revoke) URL.revokeObjectURL(gone.url);
    if (current) { S.index = -1; if (S.tracks.length) playIndex(Math.min(i, S.tracks.length - 1)); else resetStage(); }
    else if (i < S.index) S.index--;
    renderList(); renderEpisodes(); saveTracks();
  }
  function resetStage(status) {
    ++generation; clearTimeout(errorTimer); N.detach(video); S.index = -1;
    stage.classList.remove('hasvideo', 'playing'); cover.hidden = false; $('ttl').textContent = t('choose_media'); $('sub').textContent = t('drop_hint');
    $('workTitle').textContent = S.detail ? S.detail.title : cfg.title || t('title'); $('cur').textContent = $('dur').textContent = '0:00'; $('seek').value = 0;
    $('gate').hidden = true; $('snd').hidden = true; setPlaying(false); host.status(status || 'choose_media');
  }
  function safePlay(userGesture, retryMuted) {
    if (destroyed || host.suspended) return;
    if (userGesture && (!S.tracks[S.index] || video.error || video.__net && video.__net.failed)) {
      if (!S.tracks.length) { $('picker').click(); return; }
      playIndex(Math.max(0, S.index), true); return;
    }
    const ticket = generation;
    if (audioContext && audioContext.state === 'suspended') audioContext.resume().catch(() => {});
    const p = video.play();
    if (!p || !p.then) return;
    p.then(() => { if (!destroyed && ticket === generation) { $('gate').hidden = true; $('snd').hidden = !video.muted; } }).catch(error => {
      if (destroyed || ticket !== generation || host.suspended) return;
      if (pageMode !== 'index' && !video.muted && !retryMuted && error.name === 'NotAllowedError') { video.muted = true; safePlay(false, true); return; }
      $('gate').hidden = false; $('gateBtn').textContent = t(error.name === 'NotAllowedError' ? 'tap_play' : 'retry');
      $('sub').textContent = t('tap_play'); setPlaying(false);
    });
  }
  function playIndex(index, force) {
    if (destroyed || index < 0 || index >= S.tracks.length) return;
    const item = S.tracks[index];
    if (item.needsFile) { host.status('reselect'); $('picker').click(); return; }
    savePointer();
    const same = index === S.index && video.src && !force;
    S.index = index; S.epPage = Math.floor(index / PER); S.listPage = Math.floor(index / LIST_PER);
    renderList(); renderEpisodes(); savePointer();
    $('workTitle').textContent = item.name; $('ttl').textContent = item.name; $('sub').textContent = t(item.type);
    stage.classList.toggle('hasvideo', item.type === 'video'); cover.hidden = item.type === 'video';
    $('gate').hidden = true;
    if (same) { if (video.ended) video.currentTime = 0; safePlay(); return; }
    const ticket = ++generation; clearTimeout(errorTimer); host.source(item.url, item.name, item.identity);
    N.attach(video, item.url, { maxBuffer: 30, type: item.mime, referer: item.referer }).then(result => {
      if (destroyed || ticket !== generation || result.cancelled) return;
      if (result.mode === 'error') { failure(); return; }
      video.defaultPlaybackRate = video.playbackRate = S.rate; safePlay();
    });
    if (item.type === 'audio') startViz(); else stopViz();
  }
  function next(auto) {
    if (!S.tracks.length) return;
    if (auto && S.mode === 1) { video.currentTime = 0; safePlay(); return; }
    let index = S.index + 1;
    if (S.mode === 2 && S.tracks.length > 1) { index = (Math.max(0, S.index) + 1 + Math.floor(Math.random() * (S.tracks.length - 1))) % S.tracks.length; }
    if (index >= S.tracks.length) { if (auto && pageMode === 'series') { setPlaying(false); return; } index = 0; }
    playIndex(index);
  }
  function previous() { if (!S.tracks.length) return; if (video.currentTime > 3) { video.currentTime = 0; return; } playIndex((S.index - 1 + S.tracks.length) % S.tracks.length); }
  function togglePlay() { if (!video.paused) { host.pause(); return; } host.suspended = false; safePlay(true); }
  function setPlaying(playing) { $('play').textContent = playing ? '❚❚' : '▶'; $('play').setAttribute('aria-label', t(playing ? 'pause' : 'play')); cover.classList.toggle('spin', playing); stage.classList.toggle('playing', playing); }
  function failure() {
    if (destroyed) return;
    host.status('error'); $('gateBtn').textContent = t('retry'); $('gate').hidden = false; setPlaying(false);
    if (failedGeneration === generation) return;
    failedGeneration = generation;
    if (pageMode === 'index' && S.tracks.length > 1 && ++errorCount < S.tracks.length) {
      const ticket = generation; clearTimeout(errorTimer);
      errorTimer = setTimeout(() => { if (!destroyed && !host.suspended && ticket === generation) next(false); }, 900);
    }
  }
  function refreshVolume() { $('vol').value = video.muted ? 0 : Math.round(video.volume * 100); $('muteBtn').textContent = t(video.muted || !video.volume ? 'sound_on' : 'mute'); $('snd').hidden = !video.muted; }
  function toggleMute() { if (video.muted || !video.volume) { video.muted = false; video.volume = lastVol || 1; } else { lastVol = video.volume; video.muted = true; } refreshVolume(); savePreferences(); }
  function applyRate(rate) { S.rate = rate; video.defaultPlaybackRate = video.playbackRate = rate; $('spd').textContent = rate + '×'; savePreferences(); }
  function toggleList() { $('list').hidden = !$('list').hidden; $('btnList').setAttribute('aria-expanded', String(!$('list').hidden)); renderList(); }
  $('play').onclick = togglePlay; $('prev').onclick = () => { host.suspended = false; previous(); }; $('next').onclick = () => { host.suspended = false; next(false); };
  $('btnAdd').onclick = () => $('picker').click(); $('btnList').onclick = toggleList;
  $('moreBtn').onclick = () => { $('morePanel').hidden = !$('morePanel').hidden; $('moreBtn').setAttribute('aria-expanded', String(!$('morePanel').hidden)); };
  $('btnUrl').onclick = () => { $('urlbox').hidden = !$('urlbox').hidden; $('morePanel').hidden = true; if (!$('urlbox').hidden) $('urlinput').focus(); };
  $('btnInfo').onclick = () => { showInfo(); $('meta').hidden = !$('meta').hidden; };
  $('fullPage').onclick = () => host.command('fullpage');
  $('fsBtn').onclick = () => B.fullscreen(stage); $('pipBtn').onclick = () => B.pip(video);
  $('speedBtn').onclick = () => applyRate(rates[(rates.indexOf(S.rate) + 1) % rates.length]);
  $('mode').onclick = () => { S.mode = (S.mode + 1) % 3; $('mode').textContent = t(['sequential', 'repeat', 'shuffle'][S.mode]); savePreferences(); };
  $('muteBtn').onclick = toggleMute;
  $('snd').onclick = () => { host.suspended = false; video.muted = false; if (!video.volume) video.volume = lastVol || 1; safePlay(true); };
  $('gateBtn').onclick = () => { host.suspended = false; if (pageMode !== 'index') video.muted = false; safePlay(true); };
  $('clearBtn').onclick = () => { savePointer(); S.detail = null; S.identity = ''; replaceTracks([]); resetStage(); renderEpisodes(); };
  $('urlbox').onsubmit = e => { e.preventDefault(); const url = $('urlinput').value.trim(); if (!url) return; if (!B.stable(url)) { host.status('media_failed'); return; } addTracks([{ url: url }], true); $('urlinput').value = ''; $('urlbox').hidden = true; };
  const presets = [
    ['example_hls', 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'],
    ['example_hls_hd', 'https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8'],
    ['example_mp4', 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4'],
    ['example_animation', 'https://cdn.jsdelivr.net/gh/mediaelement/mediaelement-files@master/big_buck_bunny.mp4']
  ];
  $('presets').append(node('span', t('examples')));
  presets.forEach(p => $('presets').append(button(t(p[0]), () => { addTracks([{ name: t(p[0]), url: p[1] }], true); $('urlbox').hidden = true; })));
  function addFiles(files) {
    let selected = -1;
    Array.from(files).forEach(file => {
      const item = normalizeTrack({ name: file.name, url: URL.createObjectURL(file), kind: file.type.startsWith('video/') ? 'video' : file.type.startsWith('audio/') ? 'audio' : kind(file.name), mime: file.type, revoke: true });
      const at = S.tracks.findIndex(it => it.needsFile && it.name === file.name);
      if (at >= 0) S.tracks[at] = item; else S.tracks.push(item);
      if (selected < 0) selected = at >= 0 ? at : S.tracks.length - 1;
    });
    if (selected >= 0) { S.revision++; saveTracks(); host.suspended = false; playIndex(selected, true); }
  }
  $('picker').onchange = () => { addFiles($('picker').files); $('picker').value = ''; };
  $('lineSelect').onchange = () => { if (S.detail && S.detail.lines[Number($('lineSelect').value)]) adoptCatalog(S.detail, S.detail.lines[Number($('lineSelect').value)].id); };
  listen(video, 'netmedia-status', e => { host.status(e.detail.state); if (e.detail.state === 'error') failure(); });
  listen(video, 'play', () => setPlaying(true)); listen(video, 'playing', () => { errorCount = 0; $('gate').hidden = true; setPlaying(true); }); listen(video, 'pause', () => setPlaying(false));
  listen(video, 'error', failure); listen(video, 'ended', () => next(true)); listen(video, 'volumechange', refreshVolume);
  listen(video, 'loadedmetadata', () => {
    $('dur').textContent = format(video.duration); fitCanvas();
    if (video.videoWidth > 0) { stage.classList.add('hasvideo'); cover.hidden = true; stopViz(); }
  });
  video.controls = pageMode !== 'index';
  listen(document, 'fullscreenchange', () => { video.controls = pageMode !== 'index' || !!document.fullscreenElement; });
  listen(video, 'timeupdate', () => { if (S.seeking) return; $('cur').textContent = format(video.currentTime); $('seek').value = Number.isFinite(video.duration) && video.duration > 0 ? Math.round(video.currentTime / video.duration * 1000) : 0; });
  $('seek').oninput = () => { S.seeking = true; $('cur').textContent = format(Number.isFinite(video.duration) ? $('seek').value / 1000 * video.duration : 0); };
  $('seek').onchange = () => { if (Number.isFinite(video.duration) && video.duration > 0) video.currentTime = $('seek').value / 1000 * video.duration; S.seeking = false; };
  $('vol').oninput = () => { video.volume = Number($('vol').value) / 100; video.muted = video.volume === 0; if (video.volume) lastVol = video.volume; savePreferences(); };
  listen(stage, 'click', e => { if (e.target.closest('button,input,select') || video.controls && e.target === video) return; clearTimeout(dblTimer); if (!$('gate').hidden) { host.suspended = false; safePlay(true); } else dblTimer = setTimeout(togglePlay, 200); });
  listen(stage, 'dblclick', e => { if (e.target.closest('button,input,select')) return; clearTimeout(dblTimer); B.fullscreen(stage); });
  listen(document, 'keydown', e => {
    if (e.altKey || e.ctrlKey || e.metaKey || e.target.closest('input,textarea,select,button,[contenteditable=true],#videoCatalog')) return;
    const key = e.key.toLowerCase();
    if ([' ', 'arrowright', 'arrowleft', 'arrowup', 'arrowdown', 'm', 'n', 'p', 'f', 'i'].includes(key)) e.preventDefault();
    if (key === ' ') togglePlay();
    else if (key === 'arrowright') host.command('seek', video.currentTime + 5);
    else if (key === 'arrowleft') host.command('seek', video.currentTime - 5);
    else if (key === 'arrowup' || key === 'arrowdown') { video.volume = Math.max(0, Math.min(1, video.volume + (key === 'arrowup' ? 0.05 : -0.05))); video.muted = video.volume === 0; savePreferences(); }
    else if (key === 'm') toggleMute();
    else if (key === 'n') host.command('next');
    else if (key === 'p') host.command('previous');
    else if (key === 'f') B.fullscreen(stage);
    else if (key === 'i') B.pip(video);
  });
  ['dragenter', 'dragover'].forEach(event => listen(g, event, e => { e.preventDefault(); if (event === 'dragenter') dragDepth++; $('dragHint').hidden = false; }));
  ['dragleave', 'drop'].forEach(event => listen(g, event, e => { e.preventDefault(); if (event === 'dragleave' && --dragDepth > 0) return; dragDepth = 0; $('dragHint').hidden = true; if (event === 'drop' && e.dataTransfer) addFiles(e.dataTransfer.files); }));

  const ctx = canvas.getContext('2d');
  let audioContext = null, analyser = null, audioNode = null, bins = null, realViz = false, raf = 0, phase = 0;
  function fitCanvas() {
    if (destroyed || !ctx) return;
    const ratio = Math.min(g.devicePixelRatio || 1, 2), w = stage.clientWidth || 320, h = stage.clientHeight || 180;
    canvas.width = Math.round(w * ratio); canvas.height = Math.round(h * ratio); ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  }
  function draw() {
    if (destroyed || !ctx || document.hidden || stage.classList.contains('hasvideo')) { raf = 0; return; }
    const w = stage.clientWidth, h = stage.clientHeight, playing = !video.paused && !video.ended;
    phase += playing ? 0.035 : 0.008; ctx.clearRect(0, 0, w, h);
    if (realViz && analyser && bins) analyser.getByteFrequencyData(bins);
    const count = 56, width = w / count;
    for (let i = 0; i < count; i++) {
      const level = realViz && bins ? bins[Math.floor(Math.pow(i / count, 1.6) * bins.length * 0.72)] / 255 : (0.5 + 0.5 * Math.sin(phase * (1.5 + i / 10) + i * 0.44)) * (playing ? 1 : 0.12);
      const height = Math.max(2, level * h * 0.58), gradient = ctx.createLinearGradient(0, h / 2 - height / 2, 0, h / 2 + height / 2);
      gradient.addColorStop(0, '#4cc2ff'); gradient.addColorStop(1, '#8b5cf6'); ctx.fillStyle = gradient;
      ctx.fillRect(i * width + width * 0.14, h / 2 - height / 2, Math.max(1, width * 0.72), height);
    }
    raf = requestAnimationFrame(draw);
  }
  function startViz() { fitCanvas(); if (!raf) raf = requestAnimationFrame(draw); }
  function stopViz() { if (raf) cancelAnimationFrame(raf); raf = 0; }
  $('vizBtn').onclick = () => {
    if (!audioContext) {
      const AC = g.AudioContext || g.webkitAudioContext;
      if (!AC) { host.status('spectrum_unavailable'); return; }
      try { audioContext = new AC(); audioNode = audioContext.createMediaElementSource(video); analyser = audioContext.createAnalyser(); analyser.fftSize = 2048; analyser.smoothingTimeConstant = 0.82; audioNode.connect(analyser); analyser.connect(audioContext.destination); bins = new Uint8Array(analyser.frequencyBinCount); }
      catch (_) { host.status('spectrum_unavailable'); return; }
    }
    realViz = !realViz; $('vizBtn').textContent = t(realViz ? 'synthetic' : 'spectrum'); $('vizBtn').setAttribute('aria-pressed', String(realViz));
    if (audioContext.state === 'suspended') audioContext.resume().catch(() => host.status('spectrum_unavailable')); startViz();
  };
  listen(g, 'resize', fitCanvas); listen(g, 'orientationchange', fitCanvas); if (g.visualViewport) listen(g.visualViewport, 'resize', fitCanvas);
  listen(document, 'visibilitychange', () => { if (document.hidden) { hiddenPlaying = !video.paused; stopViz(); } else { fitCanvas(); if (!stage.classList.contains('hasvideo')) startViz(); if (hiddenPlaying && audioContext && !host.suspended) audioContext.resume().catch(() => {}); } });

  async function loadLibrary(automatic) {
    if (libraryController) libraryController.abort();
    const controller = new AbortController(); libraryController = controller; $('scanBtn').disabled = true; $('libraryOutput').textContent = t('loading');
    try {
      const data = await B.request('api/library', { root: $('rootInput').value || cfg.root || '~/workspace' }, controller.signal);
      if (destroyed || controller !== libraryController) return;
      addTracks(Array.isArray(data.items) ? data.items : [], false, true);
      $('libraryOutput').textContent = t('loaded_count', { total: Array.isArray(data.items) ? data.items.length : 0 });
      if (automatic && S.index < 0 && S.tracks.length) { $('ttl').textContent = t('library'); $('sub').textContent = t('library_loaded', { total: S.tracks.length }); }
    } catch (error) { if (!destroyed && controller === libraryController && error.name !== 'AbortError') $('libraryOutput').textContent = t(error.status === 503 ? 'workspace_unavailable' : 'failed'); }
    finally { if (controller === libraryController) { libraryController = null; $('scanBtn').disabled = false; } }
  }
  $('libraryTools').onsubmit = e => { e.preventDefault(); loadLibrary(false); };
  $('catalogTools').onsubmit = e => {
    e.preventDefault(); const api = $('apiInput').value.trim(), keyword = $('queryInput').value.trim();
    if (!api || !keyword) { $('catalogOutput').textContent = t('catalog_hint'); return; }
    if (catalogController) catalogController.abort();
    const controller = new AbortController(), ticket = ++catalogueGeneration; catalogController = controller; $('catalogOutput').textContent = t('loading');
    B.request('api/catalog', { api: api, query: keyword, referer: cfg.referer || '' }, controller.signal).then(data => {
      if (destroyed || ticket !== catalogueGeneration) return; data.api = data.api || api; adoptCatalog(data); $('catalogOutput').textContent = t('loaded_count', { total: S.tracks.length });
    }).catch(error => { if (!destroyed && ticket === catalogueGeneration && error.name !== 'AbortError') $('catalogOutput').textContent = t('failed'); }).finally(() => { if (ticket === catalogueGeneration) catalogController = null; });
  };

  const panel = $('videoCatalog');
  let searchGeneration = 0, searchController = null, sourcesController = null, sourcesPromise = null, sourcesLoaded = false, searchPage = 1, lastSearch = null, detail = null, detailLine = 0, detailPage = 0;
  const form = node('form', null, 'video-search-form'), searchInput = node('input'), submit = node('button', t('search_action')), cancel = button(t('cancel'), abortSearch), close = button(t('close_search'), () => showSearch(false));
  searchInput.type = 'search'; searchInput.maxLength = 512; searchInput.placeholder = t('keyword'); searchInput.setAttribute('aria-label', t('keyword')); submit.type = 'submit'; cancel.hidden = true;
  form.append(searchInput, submit, cancel, close);
  const sourceBox = node('fieldset'), searchStatus = node('output', t('search_ready'), 'video-status'), searchErrors = node('div', null, 'video-errors'), results = node('div', null, 'video-results'), resultPager = node('nav', null, 'pager'), detailBox = node('section', null, 'video-detail');
  sourceBox.append(node('legend', t('select_sources'))); searchStatus.setAttribute('role', 'status'); searchErrors.setAttribute('role', 'status'); detailBox.hidden = true;
  panel.setAttribute('aria-label', t('search')); panel.append(form, sourceBox, searchStatus, searchErrors, results, resultPager, detailBox);
  function abortSearch() { searchGeneration++; if (searchController) searchController.abort(); searchController = null; cancel.hidden = true; submit.disabled = false; searchStatus.textContent = t('search_ready'); }
  function searchRequest(route, params) {
    abortSearch(); const controller = new AbortController(), ticket = searchGeneration; searchController = controller; cancel.hidden = false; submit.disabled = true; searchStatus.textContent = t('loading');
    return B.request(route, params, controller.signal).then(data => !destroyed && ticket === searchGeneration ? data : null).catch(error => { if (!destroyed && ticket === searchGeneration && error.name !== 'AbortError') searchStatus.textContent = t('search_failed'); return null; }).finally(() => { if (ticket === searchGeneration) { searchController = null; cancel.hidden = true; submit.disabled = false; if (searchStatus.textContent === t('loading')) searchStatus.textContent = t('search_ready'); } });
  }
  function loadSources() {
    if (sourcesLoaded) return Promise.resolve(true);
    if (sourcesPromise) return sourcesPromise;
    const controller = new AbortController(); sourcesController = controller;
    sourcesPromise = B.request('api/video/sources', {}, controller.signal).then(data => {
      if (destroyed || controller !== sourcesController) return false;
      sourceBox.textContent = ''; sourceBox.append(node('legend', t('select_sources')));
      (Array.isArray(data.sources) ? data.sources : []).forEach(source => {
        if (!source || !/^[A-Za-z0-9_-]{1,64}$/.test(String(source.id))) return;
        const label = node('label'), check = node('input'); check.type = 'checkbox'; check.value = String(source.id);
        check.checked = Array.isArray(cfg.videoSources) && cfg.videoSources.length ? cfg.videoSources.includes(check.value) : source.enabled !== false;
        check.disabled = source.enabled === false; label.append(check, document.createTextNode(String(source.name || source.id))); sourceBox.append(label);
      });
      sourcesLoaded = true; return true;
    }).catch(error => { if (!destroyed && controller === sourcesController && error.name !== 'AbortError') searchErrors.textContent = t('search_failed'); return false; }).finally(() => { if (controller === sourcesController) { sourcesController = null; sourcesPromise = null; } });
    return sourcesPromise;
  }
  function showSearch(open) {
    panel.hidden = !open; $('btnVideo').setAttribute('aria-expanded', String(open));
    if (open) { loadSources(); searchInput.focus(); } else { abortSearch(); if (sourcesController) sourcesController.abort(); }
  }
  $('btnVideo').onclick = () => showSearch(panel.hidden);
  function partial(errors) { searchErrors.textContent = ''; if (Array.isArray(errors) && errors.length) { searchErrors.append(node('p', t('search_partial'))); errors.forEach(error => searchErrors.append(node('p', String(error.source || '') + ' · ' + t('search_failed')))); } }
  function search(page, reuse) {
    const keyword = reuse && lastSearch ? lastSearch.q : searchInput.value.trim();
    const selected = reuse && lastSearch ? lastSearch.sources : Array.from(sourceBox.querySelectorAll('input:checked:not(:disabled)')).map(el => el.value).join(',');
    if (!keyword) { searchStatus.textContent = t('search_ready'); return; }
    if (!selected) { searchStatus.textContent = t('select_sources'); return; }
    searchPage = page; lastSearch = { q: keyword, sources: selected }; detailBox.hidden = true; results.hidden = false; resultPager.hidden = false;
    searchRequest('api/video/search', { q: keyword, sources: selected, page: page, limit: 60 }).then(data => {
      if (!data) return; results.textContent = ''; resultPager.textContent = ''; partial(data.errors);
      const items = (Array.isArray(data.items) ? data.items : []).slice(0, 60);
      searchStatus.textContent = items.length ? t('of', { page: page, total: data.total == null ? items.length : data.total }) : t('search_empty');
      items.forEach(item => {
        const b = button('', () => loadDetail(item), 'video-result'); b.append(node('strong', item.title || item.name || item.id));
        b.append(node('span', [item.sourceName || item.source, item.year, item.remark || item.remarks, item.type || item.category].filter(Boolean).join(' · '))); results.append(b);
      });
      const prev = button(t('previous_page'), () => search(searchPage - 1, true)), nextButton = button(t('next_page'), () => search(searchPage + 1, true));
      prev.disabled = page <= 1; nextButton.disabled = data.hasMore !== true;
      resultPager.append(prev, node('span', t('page_number', { page: page })), nextButton);
    });
  }
  function loadDetail(item) {
    searchRequest('api/video/detail', { source: String(item.source), id: String(item.id) }).then(data => {
      if (!data) return; detail = normalizeDetail(data); detailLine = Number(detail.defaultLine) || 0; if (!detail.lines[detailLine]) detailLine = 0; detailPage = 0;
      const saved = B.read(B.key('video-choice', workIdentity(detail)));
      if (saved) { const at = detail.lines.findIndex(line => line.id === saved.line); if (at >= 0) { detailLine = at; const index = detail.lines[at].eps.findIndex(ep => B.stable(ep.u) === saved.url); if (index >= 0) detailPage = Math.floor(index / PER); } }
      results.hidden = true; resultPager.hidden = true; searchStatus.textContent = t('choose_episode'); partial(data.errors); renderDetail();
    });
  }
  function openVideo(data, line, index) {
    const d = normalizeDetail(data), selected = d.lines[line], ep = selected && selected.eps[index];
    if (!ep) return false;
    const identity = workIdentity(d);
    if (!B.write(B.key('video-choice', identity), { source: d.source || '', id: String(d.id || ''), line: selected.id, index: index, url: B.stable(ep.u) })) return false;
    try { sessionStorage.setItem('ajiu.video.series', JSON.stringify({ detail: d, line: selected.id, index: index })); B.flushState(); location.href = B.endpoint('series.html?video=1&source=' + encodeURIComponent(d.source || '') + '&id=' + encodeURIComponent(d.id || '')); return true; } catch (_) { return false; }
  }
  function renderDetail() {
    detailBox.hidden = false; detailBox.textContent = '';
    detailBox.append(button(t('back_results'), () => { abortSearch(); detailBox.hidden = true; results.hidden = false; resultPager.hidden = false; }), node('h2', detail.title), node('p', [detail.sourceName || detail.source, detail.year, detail.remark, detail.type].filter(Boolean).join(' · ')));
    if (detail.content) { const info = node('details'); info.append(node('summary', t('info')), node('p', detail.content)); detailBox.append(info); }
    const label = node('label', t('lines') + ' '), select = node('select'); select.id = 'videoLine'; select.setAttribute('aria-label', t('lines'));
    detail.lines.forEach((line, i) => { const option = node('option', line.name + ' · ' + line.eps.length); option.value = i; select.append(option); });
    select.value = detailLine; select.onchange = () => { detailLine = Number(select.value); detailPage = 0; renderDetail(); }; label.append(select); detailBox.append(label);
    const eps = detail.lines[detailLine] ? detail.lines[detailLine].eps : [], pages = node('nav', null, 'pager'), grid = node('div', null, 'episode-grid');
    pager(pages, detailPage, eps.length, PER, page => { detailPage = page; renderDetail(); });
    eps.slice(detailPage * PER, (detailPage + 1) * PER).forEach((ep, i) => { const b = button(ep.n, () => { if (!openVideo(detail, detailLine, detailPage * PER + i)) searchStatus.textContent = t('handoff_failed'); }); b.dataset.episode = detailPage * PER + i; grid.append(b); });
    if (!eps.length) grid.append(node('p', t('no_episodes'))); detailBox.append(pages, grid);
  }
  form.onsubmit = e => { e.preventDefault(); loadSources().then(ok => { if (ok && !destroyed && !panel.hidden) search(1, false); }); };
  cleanup.push(() => { abortSearch(); if (sourcesController) sourcesController.abort(); });
  g.AjiuVideoCatalog = { normalize: normalizeDetail, identity: workIdentity, open: openVideo, t: t };
  g.Player = { add: (url, name) => addTracks([{ url: url, name: name }], true), addMany: items => { if (Array.isArray(items)) addTracks(items, false); }, play: index => { host.suspended = false; playIndex(index); }, get tracks() { return S.tracks.slice(); } };
  g.__fullscreen = () => B.fullscreen(stage);
  g.__lockOrientation = orientation => { const value = orientation || 'landscape'; B.post('orientation', { orientation: value }); if (screen.orientation && screen.orientation.lock) return screen.orientation.lock(value).catch(() => false); return false; };
  g.__fit = fitCanvas;
  g.__state = () => ({ w: video.videoWidth, h: video.videoHeight, iw: innerWidth, ih: innerHeight, box: { w: stage.clientWidth, h: stage.clientHeight } });

  const preferences = B.read(prefsKey);
  if (preferences) {
    if ([0, 1, 2].includes(preferences.mode)) S.mode = preferences.mode;
    if (rates.includes(preferences.rate)) S.rate = preferences.rate;
    if (Number.isFinite(preferences.volume) && preferences.volume >= 0 && preferences.volume <= 1) video.volume = preferences.volume;
    video.muted = preferences.muted === true;
  }
  if (B.handoff && rates.includes(B.handoff.rate)) S.rate = B.handoff.rate;
  $('spd').textContent = S.rate + '×'; $('mode').textContent = t(['sequential', 'repeat', 'shuffle'][S.mode]); refreshVolume();
  $('rootInput').value = cfg.root || '~/workspace'; $('apiInput').value = cfg.api || ''; $('queryInput').value = cfg.query || '';
  let requestedItems = null;
  try { const raw = q.get('items'); if (raw && raw.length <= 512 * 1024) { const parsed = JSON.parse(raw); if (Array.isArray(parsed) && parsed.length <= 2000) requestedItems = parsed; } } catch (_) {}
  const items = B.handoff ? B.handoff.items.map(it => Object.assign({}, it, { identity: it.identity || B.handoff.identity || '', referer: it.referer || B.handoff.referer || '' })) : requestedItems || (q.get('list') ? q.get('list').split('|').filter(Boolean).slice(0, 2000).map(url => ({ url: url })) : Array.isArray(cfg.items) ? cfg.items : []);
  S.tracks = items.map(normalizeTrack).filter(Boolean); S.revision++;
  const requestedSource = B.handoff ? B.handoff.src : q.get('src') || cfg.src || '';
  let selected = B.handoff && Number.isInteger(B.handoff.index) && B.handoff.index >= 0 && B.handoff.index < S.tracks.length ? B.handoff.index : requestedSource ? S.tracks.findIndex(it => B.stable(it.url) === B.stable(requestedSource)) : -1;
  if (requestedSource && selected < 0) { const track = normalizeTrack({ url: requestedSource, name: B.handoff ? B.handoff.title : q.get('name') || cfg.title || '', kind: pageMode === 'index' ? kind(requestedSource) : /\.(mp3|flac|wav|m4a|ogg|opus|aac)(?:[?#]|$)/i.test(requestedSource) ? 'audio' : 'video' }); if (track) { selected = S.tracks.length; S.tracks.push(track); S.revision++; } }
  if (selected < 0 && S.tracks.length) selected = 0;
  if (!B.handoff && pageMode === 'index') {
    const restored = B.read(queueKey); if (Array.isArray(restored)) addTracks(restored, false, true);
  }
  renderList(); fitCanvas(); startViz();
  if (pageMode === 'series' && !B.handoff) {
    let handoff = null;
    try { handoff = JSON.parse(sessionStorage.getItem('ajiu.video.series')); sessionStorage.removeItem('ajiu.video.series'); } catch (_) {}
    if (q.get('video') === '1' && handoff && handoff.detail && String(handoff.detail.source || '') === (q.get('source') || '') && String(handoff.detail.id || '') === (q.get('id') || '')) adoptCatalog(handoff.detail, handoff.line, handoff.index);
    else if (q.get('video') === '1' || cfg.videoId || cfg.api) {
      const controller = new AbortController(); catalogController = controller; host.status('loading');
      const route = q.get('video') === '1' ? 'api/video/detail' : 'api/catalog', params = q.get('video') === '1' ? { source: q.get('source') || '', id: q.get('id') || '' } : {};
      B.request(route, params, controller.signal).then(data => { if (!destroyed && catalogueGeneration === 0) adoptCatalog(data); }).catch(error => { if (!destroyed && error.name !== 'AbortError') { host.status('failed'); $('catalogOutput').textContent = t('search_failed'); } });
    } else if (S.tracks.length) adoptCatalog({ title: cfg.title || t('title'), eps: S.tracks.map(it => ({ n: it.name, u: it.url, kind: it.type, mime: it.mime })) });
    else { renderEpisodes(); host.status('no_episodes'); }
  } else {
    if (selected >= 0) playIndex(selected); else host.status(S.tracks.length ? 'ready' : 'choose_media');
    saveTracks(); if (!B.handoff && pageMode === 'index') loadLibrary(true);
  }
  if (S.tracks.some(it => it.needsFile)) host.status('reselect');
  const videoQuery = q.get('videoQuery') || (!B.handoff && pageMode === 'index' && !cfg.api ? cfg.query : '');
  if (videoQuery) { showSearch(true); searchInput.value = videoQuery; loadSources().then(ok => { if (ok && !destroyed && !panel.hidden) search(1, false); }); }
})(window);

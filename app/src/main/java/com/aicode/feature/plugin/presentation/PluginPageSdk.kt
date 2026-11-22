package com.aicode.feature.plugin.presentation

internal object PluginPageSdk {
    internal val SCRIPT = """
        (function () {
          'use strict';
          if (window.aicode) return;
          var pending = Object.create(null);
          var nextId = 1;
          var backHandlers = [], eventHandlers = [], overlayHandlers = [];
          window.__aicodeBack = function () { return backHandlers.slice().reverse().some(function (handler) { try { return handler() === true; } catch (_) { return false; } }); };
          window.__aicodeEvent = function (payload) { var value; try { value = JSON.parse(payload); } catch (_) { return; } eventHandlers.slice().forEach(function (handler) { try { handler(value); } catch (_) {} }); };
          window.__aicodeOverlayMessage = function (payload) { var value; try { value = JSON.parse(payload); } catch (_) { return; } overlayHandlers.slice().forEach(function (handler) { try { handler(value); } catch (_) {} }); };
          window.__aicodeResolve = function (id, payload) {
            var entry = pending[id];
            if (!entry) return;
            delete pending[id];
            clearTimeout(entry.timer);
            var data;
            try { data = payload ? JSON.parse(payload) : null; }
            catch (e) { entry.reject(new Error('Invalid bridge response')); return; }
            if (data && data.ok === true) { entry.resolve(data.data === undefined ? null : data.data); return; }
            var error = new Error((data && data.error && data.error.message) || 'Plugin call failed');
            error.code = (data && data.error && data.error.code) || 'INTERNAL';
            entry.reject(error);
          };
          function call(method, params) {
            return new Promise(function (resolve, reject) {
              if (Object.keys(pending).length >= 32) {
                var busy = new Error('Too many pending calls'); busy.code = 'BUSY'; reject(busy); return;
              }
              var bridge = window.__aicodeBridge;
              if (!bridge) { reject(new Error('Secure bridge unavailable')); return; }
              var id = String(nextId++);
              var timer = setTimeout(function () {
                delete pending[id]; var e = new Error('Plugin call timed out'); e.code = 'TIMEOUT'; reject(e);
              }, 300000);
              pending[id] = { resolve: resolve, reject: reject, timer: timer };
              try { bridge.postMessage(JSON.stringify({ id: id, method: method, params: params || {} })); }
              catch (e) { delete pending[id]; clearTimeout(timer); reject(e); }
            });
          }
          function unsupported() {
            var e = new Error('Host method is not supported'); e.code = 'METHOD_NOT_SUPPORTED'; return Promise.reject(e);
          }
          function fileParams(path) { return { path: String(path) }; }
          var fs = {};
          ['read', 'readBinary', 'list', 'delete', 'stat', 'mkdir'].forEach(function (name) {
            fs[name] = function (path) { return call('fs.' + name, fileParams(path)); };
          });
          fs.write = function (path, content) { return call('fs.write', { path: String(path), content: String(content) }); };
          fs.append = function (path, content) { return call('fs.append', { path: String(path), content: String(content) }); };
          fs.writeBinary = function (path, base64) { return call('fs.writeBinary', { path: String(path), base64: String(base64) }); };
          fs.rename = function (from, to) { return call('fs.rename', { from: String(from), to: String(to) }); };
          fs.copy = function (from, to, options) { return call('fs.copy', { from: String(from), to: String(to), overwrite: !!(options && options.overwrite) }); };
          fs.watch = function (path, options, handler) {
            if (typeof options === 'function') { handler = options; options = {}; }
            if (typeof handler !== 'function') return Promise.reject(new TypeError('watch handler required'));
            var id = 'watch_' + String(nextId++), params = Object.assign({}, options || {}, { path: String(path), subscriptionId: id });
            var off = window.aicode.events.on(function (event) { if (event && event.name === 'fs.changed' && event.payload && event.payload.subscriptionId === id) handler(event.payload); });
            return call('fs.watch', params).then(function () { return function () { off(); return call('fs.unwatch', { subscriptionId: id }); }; }, function (error) { off(); throw error; });
          };
          window.aicode = {
            context: function () { return call('context'); },
            app: { info: function () { return call('app.info'); }, isForeground: function () { return call('app.isForeground'); } },
            invoke: function (handler, args) { return call('plugin.invoke', { handler: String(handler), args: args || {} }); },
            bash: function (command, options) {
              var params = { command: String(command) };
              if (options && options.timeout != null) params.timeout = Number(options.timeout);
              return call('bash', params);
            },
            fs: fs,
            tools: {
              list: function (options) { return call('tools.list', { includeSchema: !!(options && options.includeSchema) }); },
              pending: function (sessionId) { return call('tools.pending', sessionId == null ? {} : { sessionId: String(sessionId) }); },
              execute: function (name, args, options) {
                var params = { name: String(name), arguments: args || {} };
                if (options && options.timeoutMs != null) params.timeoutMs = Number(options.timeoutMs);
                return call('tools.execute', params);
              }
            },
            toast: function (message) { return call('toast', { message: String(message) }); },
            tip: function (options) { return call('tip', options || {}); },
            ui: {
              close: function () { return call('ui.close'); },
              openUrl: function (url) { return call('ui.openUrl', { url: String(url) }); },
              setOverlay: function (visible, rect) { return call('ui.overlay', { visible: visible !== false, rect: rect || null }); },
              onBack: function (handler) { backHandlers.push(handler); return Promise.resolve(function () { var i = backHandlers.indexOf(handler); if (i >= 0) backHandlers.splice(i, 1); }); }
            },
            overlay: {
              create: function (options) { return call('overlay.create', options || {}); },
              update: function (id, options) { return call('overlay.update', Object.assign({}, options || {}, { overlayId: String(id) })); },
              close: function (id) { return call('overlay.close', id == null ? {} : { overlayId: String(id) }); },
              self: function () { return call('overlay.self'); },
              setDragRegion: function (rect) { return call('overlay.setDragRegion', rect || {}); },
              moveBy: function (dx, dy) { return call('overlay.moveBy', { dx: Number(dx), dy: Number(dy) }); },
              resize: function (width, height, options) { return call('overlay.resize', Object.assign({}, options || {}, { width: Number(width), height: Number(height) })); },
              setVisibilityPolicy: function (policy) { return call('overlay.setVisibilityPolicy', { policy: String(policy) }); },
              onMessage: function (handler) { overlayHandlers.push(handler); return function () { var i = overlayHandlers.indexOf(handler); if (i >= 0) overlayHandlers.splice(i, 1); }; },
              postMessage: function (payload) { return call('overlay.postMessage', { payload: payload === undefined ? null : payload }); }
            },
            events: { on: function (handler) { eventHandlers.push(handler); return function () { var i = eventHandlers.indexOf(handler); if (i >= 0) eventHandlers.splice(i, 1); }; } },
            http: { request: function (options) { return call('http.request', options || {}); } },
            clipboard: { read: function () { return call('clipboard.read'); }, write: function (text) { return call('clipboard.write', { text: String(text) }); } },
            share: { text: function (text, options) { return call('share.text', Object.assign({}, options || {}, { text: String(text) })); } },
            log: { write: function (level, message, options) { return call('log.write', Object.assign({}, options || {}, { level: String(level), message: String(message) })); } },
            permissions: { request: function (options) { return call('permissions.request', options || {}); }, cancel: function (id) { return call('permissions.cancel', { requestId: String(id) }); } },
            shizuku: { exec: function (command, options) { return call('shizuku.exec', Object.assign({}, options || {}, { command: String(command) })); } },
            secrets: {
              get: function (key) { return call('secrets.get', { key: String(key) }); },
              set: function (key, value) { return call('secrets.set', { key: String(key), value: String(value) }); },
              delete: function (key) { return call('secrets.delete', { key: String(key) }); },
              list: function () { return call('secrets.list'); }
            },
            terminal: {
              run: function (command, options) { return call('terminal.run', Object.assign({}, options || {}, { command: String(command) })); },
              poll: function (id, offset) { return call('terminal.poll', { tabId: String(id), offset: Number(offset) || 0 }); },
              stop: function (id) { return call('terminal.stop', { tabId: String(id) }); },
              close: function (id) { return call('terminal.close', { tabId: String(id) }); },
              write: function (id, text, options) { return call('terminal.write', Object.assign({}, options || {}, { tabId: String(id), text: String(text) })); },
              list: function () { return call('terminal.list'); }
            },
            chat: {
              listSessions: function (options) { return call('chat.listSessions', options || {}); },
              createSession: function (options) { return call('chat.createSession', options || {}); },
              listMessages: function (id, options) { return call('chat.listMessages', Object.assign({}, options || {}, { sessionId: String(id) })); },
              sendMessage: function (id, text) { return call('chat.sendMessage', { sessionId: String(id), text: String(text) }); },
              setDraft: function (text, options) { return call('chat.setDraft', Object.assign({}, options || {}, { text: String(text) })); },
              stop: function (id) { return call('chat.stop', { sessionId: String(id) }); },
              openSession: function (id) { return call('chat.openSession', { sessionId: String(id) }); },
              getSession: function (id) { return call('chat.getSession', { sessionId: String(id) }); },
              renameSession: function (id, title) { return call('chat.renameSession', { sessionId: String(id), title: String(title) }); },
              pinSession: function (id, pinned) { return call('chat.pinSession', { sessionId: String(id), pinned: !!pinned }); },
              deleteSession: function (id) { return call('chat.deleteSession', { sessionId: String(id) }); },
              getRunState: function (id) { return call('chat.getRunState', { sessionId: String(id) }); },
              waitForRun: function (id, options) { return call('chat.waitForRun', Object.assign({}, options || {}, { sessionId: String(id) })); },
              listApprovals: function (id) { return call('chat.listApprovals', { sessionId: String(id) }); }
            },
            model: {
              listProviders: function () { return call('model.listProviders'); },
              listModels: function (id) { return call('model.listModels', { providerId: String(id && id.providerId || id || '') }); },
              create: function (options) { return call('model.create', options || {}); },
              close: function (id) { return call('model.close', { handleId: String(id && id.handleId || id || '') }); },
              start: function (options) { return call('model.start', options || {}); },
              read: function (options) { return call('model.read', options || {}); },
              cancel: function (id) { return call('model.cancel', { streamId: String(id && id.streamId || id || '') }); },
              stream: function (options, onEvent) {
                var source = Object.assign({}, options || {}), wait = source.waitMs == null ? 15000 : Number(source.waitMs); delete source.waitMs;
                return call('model.start', source).then(function (started) {
                  var cursor = 0;
                  function step() { return call('model.read', { streamId: started.streamId, cursor: cursor, waitMs: wait }).then(function (result) {
                    cursor = result.cursor; (result.events || []).forEach(function (event) { if (typeof onEvent === 'function') onEvent(event); });
                    return result.done ? result : step();
                  }); }
                  return step();
                });
              }
            },
            providers: {
              listModels: function (id) { return call('providers.listModels', { providerId: String(id) }); },
              addModel: function (id, model, options) { return call('providers.addModel', Object.assign({}, options || {}, { providerId: String(id), model: String(model) })); },
              deleteModel: function (id, model) { return call('providers.deleteModel', { providerId: String(id), model: String(model) }); },
              setModelContext: function (id, model, tokens) { return call('providers.setModelContext', { providerId: String(id), model: String(model), contextTokens: Number(tokens) }); },
              setApiKey: function (id, key) { return call('providers.setApiKey', { providerId: String(id), apiKey: String(key == null ? '' : key) }); }
            },
            mcp: { status: function () { return call('mcp.status'); } },
            sessionSource: { setMessages: function (options) { return call('sessionSource.setMessages', options || {}); }, pushEvent: function (options) { return call('sessionSource.pushEvent', options || {}); } },
            applyTheme: function (variables) {
              Object.keys(variables || {}).forEach(function (key) { document.documentElement.style.setProperty(key, variables[key]); });
            }
          };
        })();
    """.trimIndent()
}

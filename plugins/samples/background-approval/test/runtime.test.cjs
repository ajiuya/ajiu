'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { buildSync } = require('esbuild');

const source = buildSync({
  entryPoints: [path.resolve(__dirname, '../src/index.ts')],
  bundle: true, platform: 'node', format: 'cjs', target: 'node12', write: false,
  logLevel: 'silent',
  alias: { '@jieapi/plugin-sdk': path.resolve(__dirname, '../../../sdk/src/index.ts') }
}).outputFiles[0].text;

function loadRuntime() {
  let onLine;
  let seq = 0;
  const pending = new Map();
  const env = { calls: [], failCreate: false };
  const receive = message => onLine(JSON.stringify({ jsonrpc: '2.0', ...message }));
  vm.runInNewContext(source, {
    require(name) {
      assert.equal(name, 'readline');
      return { createInterface: () => ({ on: (_event, handler) => { onLine = handler; } }) };
    },
    process: { stdin: {}, stdout: { write(line) {
      const message = JSON.parse(line);
      assert.equal(message.jsonrpc, '2.0');
      if (!message.method) {
        const callback = pending.get(message.id);
        pending.delete(message.id);
        if (message.error) callback.reject(message.error);
        else callback.resolve(message.result);
        return;
      }
      env.calls.push(message);
      queueMicrotask(() => {
        if (message.method === 'host.overlay.create' && env.failCreate) {
          receive({ id: message.id, error: { code: 'OVERLAY_NOT_AUTHORIZED', message: 'not authorized' } });
        } else {
          receive({ id: message.id, result: message.method === 'host.overlay.create'
            ? { overlayId: 'demo.background-approval:ball' } : { ok: true } });
        }
      });
    } } }
  });
  env.invoke = (handler, args = {}) => new Promise((resolve, reject) => {
    const id = 'invoke-' + (++seq);
    pending.set(id, { resolve, reject });
    receive({ id, method: 'hook.invoke', params: { handler, args } });
  });
  env.message = payload => env.invoke('overlay.message', { overlayId: 'demo.background-approval:ball', payload });
  env.states = () => env.calls.filter(call => call.method === 'host.overlay.postMessage').map(call => call.params.payload);
  return env;
}

const observation = { sessionId: 'session-1', callId: 'request-1', toolName: 'Bash', arguments: { token: 'private-fixture' } };

test('background notices reuse a small overlay and never return an approval decision', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.boot');
  const result = await env.invoke('hooks.observePermission', observation);
  assert.equal(result.decision, undefined);
  assert.equal(env.calls.filter(call => call.method === 'host.overlay.create').length, 1);
  assert.deepEqual(env.calls[0].params, { surface: 'ball', width: 48, height: 48, visibilityPolicy: 'background' });
  assert.equal(env.states().at(-1).request.requestId, 'request-1');
  assert.ok(!JSON.stringify(env.states()).includes('private-fixture'));
});

test('return-to-app uses the observed session without resolving the native ticket', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.observePermission', observation);
  await env.message({ type: 'openApp', requestId: 'request-1' });
  assert.deepEqual(env.calls.at(-1).params, { sessionId: 'session-1' });
  assert.equal(env.calls.at(-1).method, 'host.chat.openSession');
  assert.ok(!env.calls.some(call => /resolve|permissions.request/.test(call.method)));
});

test('stale navigation and forged decisions cannot approve anything', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.observePermission', observation);
  assert.equal((await env.message({ type: 'openApp', requestId: 'stale' })).ok, false);
  await env.message({ type: 'decision', requestId: 'request-1', decision: 'allow' });
  assert.ok(!env.calls.some(call => /resolve|permissions|openSession/.test(call.method)));
});

test('overlay failures return observation logs and do not fabricate native approval', async () => {
  const env = loadRuntime();
  env.failCreate = true;
  const result = await env.invoke('hooks.observePermission', observation);
  assert.equal(result.decision, undefined);
  assert.ok(result.logs.length > 0);
});

test('finishing another session leaves the notice intact; the matching session clears it', async () => {
  const env = loadRuntime();
  await env.invoke('hooks.observePermission', observation);
  await env.invoke('hooks.runFinished', { sessionId: 'other' });
  assert.equal(env.states().at(-1).count, 1);
  await env.invoke('hooks.runFinished', { sessionId: 'session-1' });
  assert.deepEqual(env.states().at(-1), { type: 'approvalState', request: null, count: 0 });
});

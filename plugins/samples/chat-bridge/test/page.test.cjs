'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { transformSync } = require('esbuild');

const source = fs.readFileSync(path.resolve(__dirname, '../src/pages/chat.vue'), 'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1];
const script = transformSync(source, { loader: 'ts' }).code.replace(/^import[^;]+;\s*$/gm, '') +
  '\nglobalThis.page = { initialize, requestNativeConfirmation, cancelNativeConfirmation, originSessionId, statusText };';

function fixture(sessionId) {
  const requests = [];
  const cancellations = [];
  let decide;
  const sdk = {
    context: async () => ({ visible: true, ...(sessionId ? { sessionId } : {}) }),
    chat: {
      listMessages: async () => ({ messages: [] }),
      listApprovals: async () => ({ tool: null }),
      createSession: async () => ({ session: { id: 'created-session' } })
    },
    fs: { read: async () => ({ content: '{}' }), write: async () => {} },
    permissions: {
      request: params => { requests.push(params); return new Promise(resolve => { decide = resolve; }); },
      cancel: async requestId => { cancellations.push(requestId); return { cancelled: true }; }
    }
  };
  const context = { window: { aicode: sdk }, aicode: sdk, ref: value => ({ value }),
    computed: getter => ({ get value() { return getter(); } }), watch() {}, onMounted() {}, onBeforeUnmount() {},
    nextTick: async () => {}, setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {} };
  vm.runInNewContext(script, context);
  return { page: context.page, requests, cancellations, decide: decision => decide(decision) };
}

test('configuration pages cannot impersonate a created chat as the originating approval session', async () => {
  const env = fixture(null);
  await env.page.initialize();
  await env.page.requestNativeConfirmation();
  assert.equal(env.page.originSessionId.value, null);
  assert.equal(env.requests.length, 0);
});

test('the chat-menu demo waits for the native decision for the real originating session', async () => {
  const env = fixture('origin-session');
  await env.page.initialize();
  const waiting = env.page.requestNativeConfirmation();
  assert.equal(env.requests.length, 1);
  assert.equal(env.requests[0].sessionId, 'origin-session');
  assert.equal(env.requests[0].operation.kind, 'demo');
  assert.notEqual(env.page.statusText.value, '原生界面返回：allow');
  await env.page.cancelNativeConfirmation();
  assert.equal(env.cancellations[0], env.requests[0].requestId);
  env.decide({ decision: 'cancel' });
  await waiting;
  assert.equal(env.page.statusText.value, '原生界面返回：cancel');
});

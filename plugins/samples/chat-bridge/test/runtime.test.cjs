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

function fixture() {
  let onLine;
  const messages = [];
  vm.runInNewContext(source, {
    require(name) {
      assert.equal(name, 'readline');
      return { createInterface: () => ({ on: (_event, callback) => { onLine = callback; } }) };
    },
    process: { stdin: {}, stdout: { write(line) { messages.push(JSON.parse(line)); } } }
  });
  return {
    messages,
    async invoke(id, method, handler, args = {}) {
      onLine(JSON.stringify({ jsonrpc: '2.0', id, method, params: { handler, args } }));
      await new Promise(resolve => setImmediate(resolve));
      return messages.find(message => message.id === id);
    }
  };
}

test('permission observation returns immediately with a read-only notice and redacts arguments', async () => {
  const env = fixture();
  const reply = await env.invoke(1, 'hook.invoke', 'hooks.observePermission', {
    sessionId: 'session-1', callId: 'call-1', toolName: 'Bash',
    arguments: { Authorization: 'private-fixture' }
  });
  assert.equal(reply.jsonrpc, '2.0');
  assert.equal(reply.result.decision, undefined);
  const event = env.messages.find(message => message.method === 'plugin.event');
  assert.equal(event.params.name, 'chat.permission.requested');
  assert.equal(event.params.payload.requestId, 'call-1');
  assert.ok(!JSON.stringify(env.messages).includes('private-fixture'));
  assert.ok(!env.messages.some(message => /host\..*resolve/.test(message.method || '')));
});

test('a page cannot invoke a handler to approve native requests', async () => {
  const env = fixture();
  const reply = await env.invoke(2, 'ui.invoke', 'ui.permissionDecision', { requestId: 'call-1', decision: 'allow' });
  assert.equal(reply.error.code, 'HANDLER_NOT_FOUND');
});

test('run completion is forwarded and tool.invoke/ui.invoke remain NDJSON compatible', async () => {
  const env = fixture();
  await env.invoke(3, 'hook.invoke', 'hooks.runFinished', { sessionId: 'session-1', status: 'success' });
  assert.equal(env.messages.find(message => message.method === 'plugin.event').params.name, 'chat.run.finished');
  for (const [id, method] of [[4, 'tool.invoke'], [5, 'ui.invoke']]) {
    const reply = await env.invoke(id, method, 'actions.notifySession', { sessionId: 'session-1' });
    assert.equal(reply.jsonrpc, '2.0');
    assert.ok(reply.result.message.includes('session-'));
  }
});

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';
import vm from 'node:vm';
import { buildSync } from 'esbuild';

const require = createRequire(import.meta.url);
const { text: source } = buildSync({
  entryPoints: [new URL('../src/index.ts', import.meta.url).pathname],
  bundle: true, platform: 'node', format: 'cjs', write: false, logLevel: 'silent'
}).outputFiles[0];

function sdkFixture() {
  const messages = [];
  const module = { exports: {} };
  let onLine;
  vm.runInNewContext(source, {
    exports: module.exports, module,
    require(name) {
      if (name === 'readline') return { createInterface: () => ({ on: (_event, handler) => { onLine = handler; } }) };
      return require(name);
    },
    process: { stdin: {}, stdout: { write: line => messages.push(JSON.parse(line)) }, stderr: { write() {} } },
    AbortController, setTimeout, clearTimeout
  });
  const sdk = module.exports;
  sdk.definePlugin({ handlers: { echo: args => args } });
  return { sdk, messages, receive: value => onLine(JSON.stringify({ jsonrpc: '2.0', ...value })) };
}

test('native confirmation uses JSON-RPC request/cancel and never fabricates the host decision', async () => {
  const env = sdkFixture();
  const params = { sessionId: 's', requestId: 'ticket', title: 'Confirm', description: 'A test only', operation: { kind: 'demo' } };
  const waiting = env.sdk.permissions.request(params);
  const request = env.messages.at(-1);
  assert.deepEqual(request, { jsonrpc: '2.0', id: 'host-1', method: 'host.permissions.request', params });
  let settled = false;
  waiting.then(() => { settled = true; });
  await Promise.resolve();
  assert.equal(settled, false);
  const cancelling = env.sdk.permissions.cancel('ticket');
  const cancel = env.messages.at(-1);
  assert.equal(cancel.method, 'host.permissions.cancel');
  env.receive({ id: cancel.id, result: { cancelled: true } });
  env.receive({ id: request.id, result: { decision: 'cancel' } });
  assert.equal((await cancelling).cancelled, true);
  assert.equal((await waiting).decision, 'cancel');
});

test('tool.invoke and ui.invoke dispatch unchanged while unknown methods remain errors', async () => {
  const env = sdkFixture();
  for (const [id, method] of [[1, 'tool.invoke'], [2, 'ui.invoke']]) {
    env.receive({ id, method, params: { handler: 'echo', args: { text: method } } });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(env.messages.at(-1), { jsonrpc: '2.0', id, result: { text: method } });
  }
  env.receive({ id: 3, method: 'approval.resolve', params: { handler: 'echo' } });
  assert.equal(env.messages.at(-1).error.code, 'METHOD_UNKNOWN');
});

test('toolDelegate preserves execution identity across progress and final reads', async () => {
  const env = sdkFixture();
  const delegate = env.sdk.toolDelegate(async function* (context) {
    assert.equal(context.toolName, 'writeFile');
    assert.equal(context.arguments.path, 'a.txt');
    yield 'working';
    return { written: true };
  });
  const identity = { executionId: 'execution-1', runId: 'run-1', callId: 'call-1', toolName: 'writeFile' };
  const first = await delegate({ ...identity, operation: 'start', arguments: { path: 'a.txt' } });
  assert.equal(first.type, 'progress');
  assert.equal(first.chunk, 'working');
  const last = await delegate({ ...identity, operation: 'next' });
  assert.equal(last.type, 'completed');
  assert.equal(last.data.written, true);
  await assert.rejects(delegate({ ...identity, operation: 'start', arguments: {} }), /Duplicate/);
});

test('toolDelegate cancel aborts only the matching invocation and rejects forged identities', async () => {
  const env = sdkFixture();
  let signal;
  const delegate = env.sdk.toolDelegate(async function* (context) {
    signal = context.signal;
    yield 'ready';
    return null;
  });
  const identity = { executionId: 'execution-2', runId: 'run-2', callId: 'call-2', toolName: 'writeFile' };
  await delegate({ ...identity, operation: 'start', arguments: {} });
  await assert.rejects(delegate({ ...identity, runId: 'forged', operation: 'cancel' }), /Unknown/);
  assert.equal(signal.aborted, false);
  const result = await delegate({ ...identity, operation: 'cancel' });
  assert.equal(result.type, 'completed');
  assert.equal(signal.aborted, true);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { collectExtensionMethods, collectWhenMethods, kotlinTokens } from '../../../scripts/check-host-methods.mjs';
import { checkPageFacade, collectDeclaredPageMethods, collectPageCalls } from '../../../scripts/check-page-bridge.mjs';

test('Kotlin route parsing ignores comments, unrelated strings and nested when branches', () => {
  const source = `
    // when (api) { "tools.resolve" -> fake() }
    val denied = setOf("chat.resolveToolApproval")
    val text = "when (api) { \\"fake\\" -> ignored }"
    when (api) {
      "fs.read", "fs.stat" -> service.call()
      "tools.execute" -> {
        when (kind) { "not-a-method" -> ignored() }
        val braces = "{}"
      }
      else -> reject()
    }
  `;
  assert.deepEqual([...collectWhenMethods(source, 'api')].sort(), ['fs.read', 'fs.stat', 'tools.execute']);
});

test('method tables accept typed and inferred declarations without reading protected constants', () => {
  for (const type of ['', ': Set<String>']) {
    const source = `class Api : PluginHostApi {
      override val methods ${type} = setOf("model.start", "model.cancel")
      val protected = setOf("tools.resolve")
    }`;
    assert.deepEqual([...collectExtensionMethods(source)], ['model.start', 'model.cancel']);
  }
});

test('page call inventory includes literal calls and every explicit dynamic file method', () => {
  const source = `internal val SCRIPT = """
    ['read', 'stat'].forEach(function (name) {
      fs[name] = function (path) { return call('fs.' + name, { path }); };
    });
    call('permissions.request', options);
  """.trimIndent()`;
  assert.deepEqual([...collectPageCalls(source)].sort(), ['fs.read', 'fs.stat', 'permissions.request']);
  assert.throws(() => collectPageCalls('val SCRIPT = """call(\'tools.\' + name)""".trimIndent()'), /Unaccounted/);
});

test('page facade checks do not confuse author-facing aliases with transport methods', () => {
  const declaration = `export interface AicodeBridge {
    context(): Promise<Context>;
    ui: { setOverlay(visible: boolean): Promise<void>; };
    events: { on<T>(handler: (value: T) => void): () => void; };
    tools: { pending(sessionId?: string): Promise<Pending>; };
  }`;
  assert.deepEqual([...collectDeclaredPageMethods(declaration)], ['context', 'ui.setOverlay', 'events.on', 'tools.pending']);
  const sdk = `val SCRIPT = """window.aicode = {
    context: function () {}, ui: { setOverlay: function () {} }, events: { on: function () {} }, tools: {}
  };""".trimIndent()`;
  assert.throws(() => checkPageFacade(sdk, declaration), /tools.pending/);
  assert.equal(checkPageFacade(sdk.replace('tools: {}', 'tools: { pending: function () {} }'), declaration), 4);
});

test('invalid or duplicate method tables fail instead of producing a green partial inventory', () => {
  assert.throws(() => collectExtensionMethods('override val methods = delegated'), /explicit/);
  assert.throws(() => collectExtensionMethods('override val methods = setOf("a", "a")'), /Duplicate/);
  assert.throws(() => collectExtensionMethods('override val methods = setOf()'), /Empty/);
  assert.throws(() => kotlinTokens('/* incomplete'), /Unclosed/);
});

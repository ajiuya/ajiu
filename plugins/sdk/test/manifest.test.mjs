import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { checkRefs, validate } from '../../../scripts/check-plugin-manifests.mjs';

const schema = JSON.parse(readFileSync(new URL('../../schema/plugin.schema.json', import.meta.url), 'utf8'));
const template = JSON.parse(readFileSync(new URL('../../template/plugin.json', import.meta.url), 'utf8'));
const errorsFor = (manifest) => {
  const errors = [];
  validate(schema, manifest, 'plugin', errors, schema);
  return errors;
};
const withHook = (event, mode) => ({
  ...template,
  contributes: { hooks: [{ id: 'test', event, handler: 'hooks.test', ...(mode ? { mode } : {}) }] }
});

test('the complete manifest schema has no broken references', () => {
  assert.deepEqual(checkRefs(schema), []);
  for (const key of ['skills', 'agents', 'mcp', 'tools', 'uiEntries', 'workspaceTools', 'providers',
    'sessionSources', 'shizuku', 'backup', 'modelAccess', 'overlay']) {
    assert.ok(key in schema.properties.contributes.properties, key);
  }
});

test('permission review is an observation and cannot declare a review or transform handler', () => {
  assert.deepEqual(errorsFor(withHook('agent.permission.review')), []);
  assert.deepEqual(errorsFor(withHook('agent.permission.review', 'observe')), []);
  assert.ok(errorsFor(withHook('agent.permission.review', 'review')).length > 0);
  assert.ok(errorsFor(withHook('agent.permission.review', 'transform')).length > 0);
});

test('all writable hooks keep their exact transform declaration', () => {
  for (const event of ['agent.tool.before', 'agent.tool.after', 'agent.http.before']) {
    assert.deepEqual(errorsFor(withHook(event, 'transform')), [], event);
    assert.ok(errorsFor(withHook(event, 'observe')).length > 0, event);
    assert.ok(errorsFor(withHook(event)).length > 0, event);
  }
});

test('lifecycle hooks remain observe-only and unknown manifest fields are rejected', () => {
  assert.deepEqual(errorsFor(withHook('app.started')), []);
  assert.ok(errorsFor(withHook('app.started', 'transform')).length > 0);
  assert.ok(errorsFor({ ...template, approvalDelegate: true }).length > 0);
  const manifest = withHook('app.started');
  manifest.contributes.hooks[0].unexpected = true;
  assert.ok(errorsFor(manifest).length > 0);
});

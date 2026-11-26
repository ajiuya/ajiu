import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createRawMarkdownMiddleware, readRawMarkdown } from '../.vitepress/raw-markdown.mjs'

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'ajiu-raw-md-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const root = join(directory, 'docs')
  const outside = join(directory, 'docs-other')
  mkdirSync(join(root, 'guide'), { recursive: true })
  mkdirSync(outside)
  writeFileSync(join(root, 'index.md'), '# Root document\n')
  writeFileSync(join(root, 'guide', 'nested.md'), '# Nested document\n')
  writeFileSync(join(root, 'guide', '空 格.md'), '# Unicode document\n')
  writeFileSync(join(root, 'empty.md'), '')
  writeFileSync(join(root, 'plain.txt'), 'owned non-Markdown fixture')
  writeFileSync(join(outside, 'outside.md'), 'owned outside-root fixture')
  return { directory, root, outside }
}

function invoke(root, url) {
  const result = { headers: {}, body: undefined, nextCalls: 0, endCalls: 0 }
  createRawMarkdownMiddleware(root)({ url }, {
    setHeader(name, value) { result.headers[name] = value },
    end(body) { result.body = body; result.endCalls++ }
  }, () => { result.nextCalls++ })
  return result
}

function assertRejected(root, url) {
  assert.equal(readRawMarkdown(root, url), null, String(url))
  const result = invoke(root, url)
  assert.equal(result.nextCalls, 1, String(url))
  assert.equal(result.endCalls, 0, String(url))
  assert.equal(result.body, undefined, String(url))
  assert.deepEqual(result.headers, {}, String(url))
}

test('reads root and nested Markdown content, including encoded filenames and query suffixes', t => {
  const { root } = fixture(t)
  for (const [url, expected] of [
    ['/md/index.md', '# Root document\n'],
    ['/md/guide/nested.md?download=1#section', '# Nested document\n'],
    ['/md/guide%2Fnested.md', '# Nested document\n'],
    ['/md/guide/' + encodeURIComponent('空 格.md'), '# Unicode document\n'],
    ['/md/index.md?ignored=%', '# Root document\n']
  ]) {
    assert.equal(readRawMarkdown(root, url), expected, url)
    const result = invoke(root, url)
    assert.equal(result.body, expected, url)
    assert.equal(result.headers['Content-Type'], 'text/plain; charset=utf-8', url)
    assert.equal(result.headers['Access-Control-Allow-Origin'], '*', url)
    assert.equal(result.nextCalls, 0, url)
    assert.equal(result.endCalls, 1, url)
  }
})

test('both source and preview roots use the same middleware and preserve empty documents', t => {
  const { directory, root } = fixture(t)
  const preview = join(directory, 'dist', 'md')
  mkdirSync(preview, { recursive: true })
  writeFileSync(join(preview, 'empty.md'), '')
  for (const base of [root, preview]) {
    const result = invoke(base, '/md/empty.md?raw=1')
    assert.equal(result.body, '')
    assert.equal(result.endCalls, 1)
    assert.equal(result.nextCalls, 0)
    assert.deepEqual(result.headers, {
      'Content-Type': 'text/plain; charset=utf-8',
      'Access-Control-Allow-Origin': '*'
    })
    assertRejected(base, '/md/%2e%2e/outside.md')
  }
})

test('rejects literal and encoded traversal even when the outside Markdown file exists', t => {
  const { root } = fixture(t)
  for (const url of [
    '/md/../docs-other/outside.md',
    '/md/%2e%2e/docs-other/outside.md',
    '/md/%2E%2E%2Fdocs-other%2Foutside.md',
    '/md/.%2e/docs-other/outside.md',
    '/md/guide/../../docs-other/outside.md',
    '/md/guide%2f..%2f..%2fdocs-other%2foutside.md',
    '/md/guide/../index.md',
    '/md/%252e%252e%252fdocs-other%252foutside.md'
  ]) assertRejected(root, url)
})

test('rejects absolute paths and Windows path forms instead of replacing the allowed root', t => {
  const { root, outside } = fixture(t)
  const file = join(outside, 'outside.md')
  for (const url of [
    '/md/' + file,
    '/md/' + encodeURIComponent(file),
    '/md/%2f%2fexample.invalid/share.md',
    '/md/C:/fixture.md',
    '/md/C:fixture.md',
    '/md/' + encodeURIComponent('C:\\fixture.md'),
    '/md/' + encodeURIComponent('\\\\host\\share\\fixture.md')
  ]) assertRejected(root, url)
})

test('malformed encoding, NUL and encoded backslashes return next without throwing', t => {
  const { root } = fixture(t)
  for (const url of [
    '/md/%.md', '/md/%2.md', '/md/%GG.md', '/md/%E0%A4%A.md',
    '/md/%00index.md', '/md/index.md\0', '/md/guide%5cnested.md',
    '/md/..%5cdocs-other%5coutside.md'
  ]) assertRejected(root, url)
})

test('missing files, missing roots, non-Markdown files, directories and other routes fall through', t => {
  const { directory, root } = fixture(t)
  mkdirSync(join(root, 'folder.md'))
  for (const url of [
    '/md/missing.md', '/md/plain.txt', '/md/plain%2etxt', '/md/folder.md',
    '/md/', '/md', '/other/index.md', '/index.md', undefined
  ]) assertRejected(root, url)
  assertRejected(join(directory, 'missing-root'), '/md/index.md')
  assertRejected(join(root, 'index.md'), '/md/index.md')
})

test('rejects file and directory symlinks escaping to a sibling with the same prefix', t => {
  const { root, outside } = fixture(t)
  symlinkSync(join(outside, 'outside.md'), join(root, 'external.md'))
  symlinkSync(outside, join(root, 'external-dir'), 'dir')
  assertRejected(root, '/md/external.md')
  assertRejected(root, '/md/external-dir/outside.md')
})

test('allows symlinks resolving to Markdown files within the canonical root', t => {
  const { root } = fixture(t)
  symlinkSync(join(root, 'guide', 'nested.md'), join(root, 'alias.md'))
  symlinkSync(join(root, 'guide'), join(root, 'alias-dir'), 'dir')
  assert.equal(readRawMarkdown(root, '/md/alias.md'), '# Nested document\n')
  assert.equal(readRawMarkdown(root, '/md/alias-dir/nested.md'), '# Nested document\n')
})

test('a Markdown alias cannot expose a non-Markdown target and invalid links fall through', t => {
  const { root } = fixture(t)
  symlinkSync(join(root, 'plain.txt'), join(root, 'disguised.md'))
  symlinkSync(join(root, 'missing.md'), join(root, 'dangling.md'))
  symlinkSync('loop.md', join(root, 'loop.md'))
  for (const url of ['/md/disguised.md', '/md/dangling.md', '/md/loop.md']) {
    assertRejected(root, url)
  }
})

#!/usr/bin/env node
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BASE = 'app/src/main/java/com/aicode/feature/plugin';
const SDK = 'plugins/sdk/types/host.d.ts';
export const protectedMethods = new Set(['tools.resolve', 'chat.resolveToolApproval', 'chat.resolveQuestion', 'chat.resolvePlanApproval']);
export const pageOnlyMethods = new Set(['context', 'fs.watch', 'fs.unwatch', 'toast', 'ui.close', 'ui.overlay',
  'overlay.self', 'overlay.setDragRegion', 'overlay.moveBy', 'overlay.resize', 'overlay.setVisibilityPolicy', 'log.write']);

// Kotlin 字符串和注释必须先词法隔离，避免把拒绝表、注释或嵌套 when 当作分发表。
export function kotlinTokens(source) {
  const tokens = [];
  for (let i = 0; i < source.length;) {
    if (/\s/.test(source[i])) { i++; continue; }
    if (source.startsWith('//', i)) { i = source.indexOf('\n', i) < 0 ? source.length : source.indexOf('\n', i); continue; }
    if (source.startsWith('/*', i)) {
      let depth = 1; i += 2;
      while (i < source.length && depth) {
        if (source.startsWith('/*', i)) { depth++; i += 2; }
        else if (source.startsWith('*/', i)) { depth--; i += 2; }
        else i++;
      }
      if (depth) throw new Error('Unclosed Kotlin comment');
      continue;
    }
    if (source.startsWith('"""', i)) {
      const end = source.indexOf('"""', i + 3);
      if (end < 0) throw new Error('Unclosed Kotlin raw string');
      tokens.push({ kind: 'string', value: source.slice(i + 3, end) }); i = end + 3; continue;
    }
    if (source[i] === '"' || source[i] === "'") {
      const quote = source[i++]; let value = ''; let closed = false;
      while (i < source.length) {
        const c = source[i++];
        if (c === '\\') { value += c + source[i++]; continue; }
        if (c === quote) { closed = true; break; }
        value += c;
      }
      if (!closed) throw new Error('Unclosed Kotlin string');
      tokens.push({ kind: quote === '"' ? 'string' : 'char', value }); continue;
    }
    const word = /^[A-Za-z_][\w]*/.exec(source.slice(i));
    if (word) { tokens.push({ kind: 'word', value: word[0] }); i += word[0].length; continue; }
    const value = source.startsWith('->', i) ? '->' : source[i];
    tokens.push({ kind: 'punct', value }); i += value.length;
  }
  return tokens;
}

export function collectWhenMethods(source, selector) {
  const tokens = kotlinTokens(source);
  const methods = new Set();
  for (let i = 0; i < tokens.length - 5; i++) {
    if (tokens.slice(i, i + 5).map(t => t.value).join(' ') !== `when ( ${selector} ) {`) continue;
    let depth = 1;
    for (let j = i + 5; j < tokens.length && depth; j++) {
      const token = tokens[j];
      if (token.value === '{' && token.kind === 'punct') { depth++; continue; }
      if (token.value === '}' && token.kind === 'punct') { depth--; continue; }
      if (depth !== 1 || token.kind !== 'string') continue;
      const labels = [token.value]; let end = j + 1;
      while (tokens[end]?.value === ',' && tokens[end + 1]?.kind === 'string') {
        labels.push(tokens[end + 1].value); end += 2;
      }
      if (tokens[end]?.value === '->') {
        for (const label of labels) methods.add(label);
        j = end;
      }
    }
  }
  return methods;
}

export function collectExtensionMethods(source) {
  const tokens = kotlinTokens(source);
  const start = tokens.findIndex((token, i) => token.value === 'override' &&
    tokens[i + 1]?.value === 'val' && tokens[i + 2]?.value === 'methods');
  if (start < 0) throw new Error('PluginHostApi must declare override val methods = setOf(...)');
  let assignment = start + 3;
  while (tokens[assignment] && !['=', '{', '}'].includes(tokens[assignment].value)) assignment++;
  if (tokens.slice(assignment, assignment + 3).map(t => t.value).join(' ') !== '= setOf (') {
    throw new Error('PluginHostApi methods must be an explicit setOf(...)');
  }
  const methods = new Set(); let end = assignment + 3;
  while (tokens[end]?.value !== ')') {
    const token = tokens[end++];
    if (!token || token.kind !== 'string' && token.value !== ',') throw new Error('Method table must contain explicit string literals');
    if (token.kind === 'string') {
      if (methods.has(token.value)) throw new Error(`Duplicate host method: ${token.value}`);
      methods.add(token.value);
    }
  }
  if (!methods.size) throw new Error('Empty PluginHostApi method table');
  return methods;
}

export function collectHostSurface(project = root) {
  const owners = new Map();
  const files = [];
  const add = (methods, file) => {
    for (const method of methods) {
      if (owners.has(method)) throw new Error(`Duplicate host routing ${method}: ${owners.get(method)} / ${file}`);
      owners.set(method, file);
    }
  };
  const gateway = `${BASE}/runtime/PluginHostGateway.kt`;
  const builtin = collectWhenMethods(readFileSync(join(project, gateway), 'utf8'), 'api');
  if (!builtin.size) throw new Error(`No gateway routes parsed: ${gateway}`);
  add(builtin, gateway); files.push(gateway);
  for (const directory of ['runtime', 'presentation']) {
    for (const name of readdirSync(join(project, BASE, directory)).filter(name => name.endsWith('.kt')).sort()) {
      const file = `${BASE}/${directory}/${name}`;
      const source = readFileSync(join(project, file), 'utf8');
      if (!/:\s*PluginHostApi\b/.test(source)) continue;
      const methods = collectExtensionMethods(source);
      const branches = collectWhenMethods(source, 'method');
      if (branches.size) {
        for (const method of methods) {
          if (!branches.has(method)) throw new Error(`Advertised method has no dispatch branch: ${method} (${file})`);
        }
      }
      add(methods, file); files.push(file);
    }
  }
  if (files.length < 2) throw new Error('No PluginHostApi extensions parsed');
  return { methods: new Set(owners.keys()), owners, files };
}

export function checkHostMethods(project = root) {
  const surface = collectHostSurface(project);
  const declared = new Set([...readFileSync(join(project, SDK), 'utf8')
    .matchAll(/^\s*'(host\.[A-Za-z0-9_.]+)'\s*:/gm)].map(match => match[1]));
  if (!declared.size) throw new Error(`No SDK methods parsed: ${SDK}`);
  const host = new Set([...surface.methods].filter(method => !pageOnlyMethods.has(method)).map(method => `host.${method}`));
  const missingSdk = [...host].filter(method => !declared.has(method)).sort();
  const missingHost = [...declared].filter(method => !host.has(method)).sort();
  const forbidden = [...surface.methods].filter(method => protectedMethods.has(method));
  if (missingSdk.length || missingHost.length || forbidden.length) {
    throw new Error([
      missingSdk.length ? `Host methods missing from SDK:\n${missingSdk.join('\n')}` : '',
      missingHost.length ? `SDK methods missing from host:\n${missingHost.join('\n')}` : '',
      forbidden.length ? `Protected user decisions exposed:\n${forbidden.join('\n')}` : ''
    ].filter(Boolean).join('\n\n'));
  }
  return { count: declared.size, files: surface.files.map(file => relative(project, join(project, file))) };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    const result = checkHostMethods();
    console.log(`插件 host 静态方法表一致：${result.count} 个进程接口，${result.files.length} 个真实分发源；非运行期 E2E。`);
  } catch (error) {
    console.error(`插件 host 静态对账失败：${error.message}`);
    process.exitCode = 1;
  }
}

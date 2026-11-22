#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { collectHostSurface, collectWhenMethods, kotlinTokens, protectedMethods, root } from './check-host-methods.mjs';

const BASE = 'app/src/main/java/com/aicode/feature/plugin';
const PAGE_SDK = `${BASE}/presentation/PluginPageSdk.kt`;
const PAGE = `${BASE}/presentation/NativePluginPage.kt`;
const OVERLAY = `${BASE}/overlay/PluginOverlayManager.kt`;
const ACTIONS = `${BASE}/runtime/PluginActions.kt`;
const GATEWAY = `${BASE}/runtime/PluginHostGateway.kt`;
const OVERLAY_LOCAL = new Set(['overlay.self', 'overlay.setDragRegion', 'overlay.moveBy',
  'overlay.resize', 'overlay.setVisibilityPolicy']);

export function extractPageScript(source) {
  const match = /\bval SCRIPT\s*=\s*"""([\s\S]*?)"""\.trimIndent\(\)/.exec(source);
  if (!match) throw new Error(`Unable to extract actual JavaScript from ${PAGE_SDK}`);
  return match[1];
}

export function collectPageCalls(source) {
  const script = extractPageScript(source);
  const methods = new Set([...script.matchAll(/\bcall\(\s*'([A-Za-z][\w.]*)'\s*[,)]/g)].map(match => match[1]));
  const dynamic = [...script.matchAll(/\bcall\(\s*'([^']*)'\s*\+/g)].map(match => match[1]);
  for (const prefix of dynamic) {
    if (prefix !== 'fs.') throw new Error(`Unaccounted dynamic page route prefix: ${prefix}`);
    const loop = /\[([^\]]+)\]\.forEach\(function\s*\(name\)\s*\{\s*fs\[name\][\s\S]*?call\('fs\.'\s*\+\s*name/.exec(script);
    if (!loop) throw new Error('Cannot determine dynamic fs page routes');
    const names = [...loop[1].matchAll(/'([A-Za-z][\w]*)'/g)].map(match => match[1]);
    if (!names.length) throw new Error('No explicit dynamic fs names parsed');
    for (const name of names) methods.add(prefix + name);
  }
  if (!methods.size) throw new Error('No page SDK transport calls parsed');
  return methods;
}

export function collectDeclaredPageMethods(source) {
  const tokens = kotlinTokens(source);
  const start = tokens.findIndex((token, i) => token.value === 'interface' && tokens[i + 1]?.value === 'AicodeBridge');
  if (start < 0 || tokens[start + 2]?.value !== '{') throw new Error('No AicodeBridge interface parsed');
  const methods = new Set();
  const members = (open, prefix) => {
    let i = open + 1;
    while (tokens[i]?.value !== '}') {
      const name = tokens[i];
      if (!name || name.kind !== 'word') throw new Error('Unrecognized page interface member');
      if (tokens[i + 1]?.value === ':' && tokens[i + 2]?.value === '{') {
        i = members(i + 2, prefix + name.value + '.') + 1;
        if (tokens[i]?.value !== ';') throw new Error('Unterminated page namespace declaration');
        i++;
        continue;
      }
      if (!['(', '<'].includes(tokens[i + 1]?.value)) throw new Error(`Unrecognized page method: ${prefix + name.value}`);
      methods.add(prefix + name.value);
      let braces = 0, parens = 0, brackets = 0;
      while (tokens[++i]) {
        const token = tokens[i];
        if (token.kind !== 'punct') continue;
        if (token.value === '{') braces++;
        if (token.value === '}') braces--;
        if (token.value === '(') parens++;
        if (token.value === ')') parens--;
        if (token.value === '[') brackets++;
        if (token.value === ']') brackets--;
        if (token.value === ';' && !braces && !parens && !brackets) { i++; break; }
      }
    }
    return i;
  };
  members(start + 2, '');
  if (!methods.size) throw new Error('Empty AicodeBridge method inventory');
  return methods;
}

export function checkPageFacade(scriptSource, declarationSource) {
  const sandbox = { window: {}, document: { documentElement: { style: { setProperty() {} } } } };
  const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext(extractPageScript(scriptSource), context, { timeout: 1000, filename: 'PluginPageSdk.js' });
  const methods = collectDeclaredPageMethods(declarationSource);
  const missing = [];
  for (const method of methods) {
    const value = method.split('.').reduce((object, key) => object?.[key], sandbox.window.aicode);
    if (typeof value !== 'function') missing.push(method);
  }
  if (missing.length) throw new Error(`Page type declarations missing from the actual facade:\n${missing.join('\n')}`);
  return methods.size;
}

export function checkPageBridge(project = root) {
  const read = file => readFileSync(join(project, file), 'utf8');
  const surface = collectHostSurface(project);
  const pageMethods = collectWhenMethods(read(PAGE), 'method');
  const overlayMethods = collectWhenMethods(read(OVERLAY), 'method');
  if (!pageMethods.size || !overlayMethods.size) throw new Error('Page or overlay local routing is empty');
  const actions = read(ACTIONS);
  if (!/if\s*\(method\s*!=\s*"plugin\.invoke"\)\s*return gateway\.call/.test(actions)) {
    throw new Error('plugin.invoke special routing is not verifiable');
  }
  pageMethods.add('plugin.invoke');
  const aliases = new Map([...read(GATEWAY).matchAll(/"([\w.]+)"\s*->\s*"([\w.]+)"/g)]
    .filter(match => surface.methods.has(match[2])).map(match => [match[1], match[2]]));
  const facadeCount = checkPageFacade(read(PAGE_SDK), read('plugins/sdk/types/aicode.d.ts'));
  const routes = collectPageCalls(read(PAGE_SDK));
  const problems = [];
  for (const method of routes) {
    if (protectedMethods.has(method)) { problems.push(`Protected user decision exposed by page SDK: ${method}`); continue; }
    if (OVERLAY_LOCAL.has(method)) {
      if (!overlayMethods.has(method)) problems.push(`Overlay-local SDK method has no overlay route: ${method}`);
    } else if (!surface.methods.has(method) && !pageMethods.has(method) && !aliases.has(method)) {
      problems.push(`Page SDK call has no normal-page or host route: ${method}`);
    }
  }
  if (problems.length) throw new Error(problems.join('\n'));
  return { count: routes.size, facadeCount, hostCount: surface.methods.size, pageCount: pageMethods.size, overlayCount: overlayMethods.size };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    const result = checkPageBridge();
    console.log(`插件页面桥静态对账一致：${result.count} 个真实 SDK 调用、${result.facadeCount} 个声明外观方法存在性，宿主 ${result.hostCount}、页面局部 ${result.pageCount}、浮层局部 ${result.overlayCount}；非运行期 E2E。`);
  } catch (error) {
    console.error(`插件页面桥静态对账失败：${error.message}`);
    process.exitCode = 1;
  }
}

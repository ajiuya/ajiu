import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';
import { translations } from '../src/composables/useLocale.js';

const server = await createServer({
  root: fileURLToPath(new URL('..', import.meta.url)),
  configFile: false,
  plugins: [vue()],
  server: { middlewareMode: true, hmr: false }
});
after(() => server.close());
const { render } = await server.ssrLoadModule('/src/entry-server.js');

for (const [locale, path] of [['zh-CN', '/'], ['en', '/en/']]) {
  test(`${locale} prerender identifies the independent workbench and its distribution policy`, async () => {
    const html = await render(path);
    const site = translations[locale].site;
    assert.ok(html.includes(site.brand));
    assert.ok(html.includes(site.download.packageDescription));
    assert.ok(html.includes(site.sponsors.subtitle));
    assert.ok(html.includes(site.footer.copyright));
    assert.doesNotMatch(html, /BUILD|PLAN|AUTO|cycleMode/);
    assert.ok(!html.includes('href="https://aicode.murk.top/download"'));
    for (const text of [site.hero.download, site.cta.download]) {
      const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      assert.match(html, new RegExp('href="#download"[^>]*>[\\s\\S]*?' + escaped));
    }
  });
}

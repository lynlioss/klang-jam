import { shareImage } from './share-image.js';

const escape = (s) => s.replace(/[&<>"']/g, (c) => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
export default {
  async fetch(request, env, context) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/.well-known/') || url.pathname.startsWith('/assets/')) return env.ASSETS.fetch(request);
    if (url.pathname.startsWith('/share/')) {
      const match = /^\/share\/v1\/([a-z0-9]{4})\.png$/.exec(url.pathname);
      if (!match) return new Response('Image not found', {status: 404});
      const cache = globalThis.caches?.default;
      const cacheKey = new Request(`https://jamklang.pages.dev/share/v1/${match[1]}.png`);
      const cached = cache ? await cache.match(cacheKey) : null;
      if (cached) return cached;
      const image = await shareImage(match[1], env);
      if (cache && context) context.waitUntil(cache.put(cacheKey, image.clone()));
      return image;
    }
    let code;
    try { code = decodeURIComponent(url.pathname.replace(/^\/+|\/+$/g, '')).toLowerCase(); }
    catch { return new Response('Invalid invite', {status: 400}); }
    if (code && !/^[a-z0-9]{4}$/.test(code)) return new Response('This invite needs four letters or numbers.', {status: 404});
    const asset = await env.ASSETS.fetch(new URL('/index.html', url));
    const canonical = `https://jamklang.pages.dev/${code}`;
    const image = code ? `https://jamklang.pages.dev/share/v1/${code}.png` : 'https://jamklang.pages.dev/assets/klang-share.png';
    const html = (await asset.text())
      .replaceAll('__CODE__', escape(code))
      .replaceAll('__TITLE__', 'Join this Jam on Klang!')
      .replaceAll('__DESCRIPTION__', code ? `Use invite code ${code} to listen together.` : 'Open Klang to listen together.')
      .replaceAll('__URL__', canonical)
      .replaceAll('__IMAGE__', image)
      .replaceAll('__IMAGE_ALT__', code ? `Klang logo and Jam invite code ${code}` : 'Klang logo');
    return new Response(html, {headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'public, max-age=60',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
      'content-security-policy': "default-src 'none'; img-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
    }});
  },
};

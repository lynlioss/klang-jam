const escape = (s) => s.replace(/[&<>"']/g, (c) => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/.well-known/')) return env.ASSETS.fetch(request);
    let code;
    try { code = decodeURIComponent(url.pathname.replace(/^\/+|\/+$/g, '')).toLowerCase(); }
    catch { return new Response('Invalid invite', {status: 400}); }
    if (code && !/^[a-z0-9]{4}$/.test(code)) return new Response('This invite needs four letters or numbers.', {status: 404});
    const asset = await env.ASSETS.fetch(new URL('/index.html', url));
    const html = (await asset.text()).replaceAll('__CODE__', escape(code)).replaceAll('__TITLE__',
      code ? `Join Jam ${escape(code)} · Klang` : 'Listen together · Klang');
    return new Response(html, {headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'public, max-age=60',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
      'content-security-policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
    }});
  },
};

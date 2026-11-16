import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {inflateSync} from 'node:zlib';
import worker from './_worker.js';
const env = {ASSETS:{fetch:async request => {
 const path = new URL(request.url ?? request).pathname;
 try { return new Response(await readFile(new URL('.' + path, import.meta.url))); }
 catch { return new Response('Not found', {status:404}); }
}}};
test('four-character invites are normalized and embedded', async()=>{
 const r=await worker.fetch(new Request('https://jamklang.pages.dev/XB7E'),env);
 assert.equal(r.status,200);
 const html=await r.text();
 assert.match(html, /Join this Jam on Klang!/);
 assert.match(html, /Use invite code xb7e to listen together/);
 assert.match(html, /property="og:image" content="https:\/\/jamklang.pages.dev\/share\/v1\/xb7e.png"/);
 assert.match(html, /name="twitter:card" content="summary_large_image"/);
 assert.match(html, /name="twitter:image" content="https:\/\/jamklang.pages.dev\/share\/v1\/xb7e.png"/);
 assert.doesNotMatch(html, /__[A-Z_]+__/);
});
test('invalid, encoded and oversized paths cannot enter the page script',async()=>{
 for(const path of ['abc','abcde','%3Csvg%3E','%27%3Balert(1)%3B','%ZZ','a/b']) {
  const r=await worker.fetch(new Request('https://jamklang.pages.dev/'+path),env);assert.ok(r.status>=400);
 }
});
test('Android asset links remain accessible',async()=>{
 const r=await worker.fetch(new Request('https://jamklang.pages.dev/.well-known/assetlinks.json'),env);assert.equal(r.status,200);
});
test('share images are valid PNGs with unique pixels for each code', async()=>{
 const images=[];
 for(const code of ['v2sl','wwww','gggg','iiii']) {
  const r=await worker.fetch(new Request(`https://jamklang.pages.dev/share/v1/${code}.png`),env);
  assert.equal(r.status,200);
  assert.equal(r.headers.get('content-type'),'image/png');
  const png=Buffer.from(await r.arrayBuffer());
  assert.deepEqual([...png.subarray(0,8)],[137,80,78,71,13,10,26,10]);
  assert.equal(png.readUInt32BE(16),1200);
  assert.equal(png.readUInt32BE(20),630);
  const length=png.readUInt32BE(33);
  const pixels=inflateSync(png.subarray(41,41+length));
  assert.equal(pixels.length,630*(1200*3+1));
  images.push(pixels);
 }
 assert.notDeepEqual(images[0],images[1]);
});
test('image routes reject malformed codes and root has a fallback image',async()=>{
 for(const path of ['share/v1/abc.png','share/v1/%3Csvg%3E.png','share/v1/aaaa.svg']) {
  assert.equal((await worker.fetch(new Request('https://jamklang.pages.dev/'+path),env)).status,404);
 }
 const root=await worker.fetch(new Request('https://jamklang.pages.dev/'),env);
 assert.match(await root.text(),/https:\/\/jamklang.pages.dev\/assets\/klang-share.png/);
 const fallback=await worker.fetch(new Request('https://jamklang.pages.dev/assets/klang-share.png'),env);
 assert.equal(fallback.status,200);
});

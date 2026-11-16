import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './_worker.js';
const env = {ASSETS:{fetch:async () => new Response('<title>__TITLE__</title><div>__CODE__</div>')}};
test('four-character invites are normalized and embedded', async()=>{
 const r=await worker.fetch(new Request('https://jamklang.pages.dev/XB7E'),env);
 assert.equal(r.status,200);assert.match(await r.text(),/Join Jam xb7e/);
});
test('invalid, encoded and oversized paths cannot enter the page script',async()=>{
 for(const path of ['abc','abcde','%3Csvg%3E','%27%3Balert(1)%3B','%ZZ','a/b']) {
  const r=await worker.fetch(new Request('https://jamklang.pages.dev/'+path),env);assert.ok(r.status>=400);
 }
});
test('Android asset links remain accessible',async()=>{
 const r=await worker.fetch(new Request('https://jamklang.pages.dev/.well-known/assetlinks.json'),env);assert.equal(r.status,200);
});

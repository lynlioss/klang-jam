// The logo and font glyphs are rasterized once when editing the site. On the edge,
// compose the code into those pixels and encode a PNG using native compression.
// This avoids browser rendering, external image services and runtime dependencies.
const WIDTH = 1200;
const HEIGHT = 630;
let assetsPromise;

async function transform(bytes, stream) {
  return new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(stream)).arrayBuffer());
}

function fromBase64(value) {
  return Uint8Array.from(atob(value), char => char.charCodeAt(0));
}

async function loadAssets(env) {
  const response = await env.ASSETS.fetch(new URL('https://jamklang.pages.dev/assets/share-data.json'));
  if (!response.ok) throw new Error('Share image assets are unavailable');
  const data = await response.json();
  const base = await transform(fromBase64(data.base), new DecompressionStream('deflate'));
  const glyphs = {};
  await Promise.all(Object.entries(data.glyphs).map(async ([letter, glyph]) => {
    glyphs[letter] = {...glyph, pixels: await transform(fromBase64(glyph.pixels), new DecompressionStream('deflate'))};
  }));
  return {base, glyphs};
}

const crcTable = Uint32Array.from({length: 256}, (_, value) => {
  for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  return value >>> 0;
});

function chunk(type, data) {
  const bytes = new Uint8Array(data.length + 12);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, data.length);
  bytes.set(new TextEncoder().encode(type), 4);
  bytes.set(data, 8);
  let crc = 0xffffffff;
  for (let index = 4; index < bytes.length - 4; index++) crc = crcTable[(crc ^ bytes[index]) & 255] ^ (crc >>> 8);
  view.setUint32(bytes.length - 4, (crc ^ 0xffffffff) >>> 0);
  return bytes;
}

async function png(rgb) {
  const header = new Uint8Array(13);
  const view = new DataView(header.buffer);
  view.setUint32(0, WIDTH);
  view.setUint32(4, HEIGHT);
  header[8] = 8; // RGB, 8 bits per channel.
  header[9] = 2;
  const scanlines = new Uint8Array(HEIGHT * (WIDTH * 3 + 1));
  for (let row = 0; row < HEIGHT; row++) scanlines.set(rgb.subarray(row * WIDTH * 3, (row + 1) * WIDTH * 3), row * (WIDTH * 3 + 1) + 1);
  const compressed = await transform(scanlines, new CompressionStream('deflate'));
  return new Blob([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', compressed), chunk('IEND', new Uint8Array())]);
}

export async function shareImage(code, env) {
  if (!assetsPromise) assetsPromise = loadAssets(env).catch(error => { assetsPromise = undefined; throw error; });
  const {base, glyphs} = await assetsPromise;
  const rgb = base.slice();
  const letters = [...code].map(letter => glyphs[letter]);
  const gap = 5;
  const width = letters.reduce((sum, glyph) => sum + glyph.width, 0) + gap * (letters.length - 1);
  // Fit even the widest codes in the right-hand region without clipping.
  const scale = Math.min(1, 590 / width);
  let left = 540 + (590 - width * scale) / 2;
  for (const glyph of letters) {
    const w = Math.ceil(glyph.width * scale);
    const h = Math.ceil(glyph.height * scale);
    const top = Math.round(300 - h / 2);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const alpha = glyph.pixels[Math.min(glyph.height - 1, Math.floor(y / scale)) * glyph.width + Math.min(glyph.width - 1, Math.floor(x / scale))] / 255;
      if (!alpha) continue;
      const index = ((top + y) * WIDTH + Math.round(left) + x) * 3;
      for (let channel = 0; channel < 3; channel++) rgb[index + channel] = Math.round(rgb[index + channel] * (1 - alpha) + 245 * alpha);
    }
    left += (glyph.width + gap) * scale;
  }
  return new Response(await png(rgb), {headers: {
    'content-type': 'image/png',
    'cache-control': 'public, max-age=31536000, immutable',
    'x-content-type-options': 'nosniff',
  }});
}

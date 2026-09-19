// Tiny pixel-art toolkit: palette, grids, compositing, auto-outline, PNG/GIF export.
const fs = require('fs');
const { PNG } = require('pngjs');

// Clean palette distilled from the CatPack orange cat.
const PALETTE = {
  o: '#212121', // outline
  f: '#fdd5b5', // fur
  s: '#d78c77', // stripes / inner ear
  d: '#e6b4a0', // fur shade / far legs
  c: '#fef5e6', // cream (muzzle, belly, paws)
  b: '#eb9486', // blush
  k: '#000000', // eye
  w: '#ffffff', // eye highlight
  p: '#e2b6ac', // collar
  y: '#f1b29e', // collar tag
  // props
  K: '#5b6275', // keyboard body
  L: '#3e4454', // keyboard dark edge
  j: '#e9ecf3', // key
  J: '#9fe3ff', // pressed key glow
  r: '#e0566b', // yarn ball red
  R: '#a8364a', // yarn ball dark
  h: '#ff6f91', // heart
  H: '#c2405f', // heart dark
  z: '#8fb8ff', // z's / thought accents
  e: '#ffd24d', // exclamation yellow
  B: '#e4a567', // box
  D: '#b3644d', // box side
  E: '#994f39', // box edge
  n: '#f7e7c6', // bed cushion
  g: '#e3f4ff', // glass (empty part)
  q: '#7cc8f2', // water
  Q: '#4a9fd8', // water, deeper
};

const hex2rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const RGB = Object.fromEntries(Object.entries(PALETTE).map(([k, v]) => [k, hex2rgb(v)]));

// A grid is an array of strings; '.' = transparent.
const blank = (w, h) => Array.from({ length: h }, () => '.'.repeat(w));
const toArr = g => g.map(r => r.split(''));
const fromArr = a => a.map(r => r.join(''));
const W = g => g[0].length;
const Hh = g => g.length;

function get(g, x, y) { return y < 0 || y >= g.length || x < 0 || x >= g[0].length ? '.' : g[y][x]; }

// Paste src onto dst at (dx,dy); '.' in src is transparent.
function paste(dst, src, dx, dy) {
  const a = toArr(dst);
  src.forEach((row, y) => [...row].forEach((ch, x) => {
    const X = dx + x, Y = dy + y;
    if (ch !== '.' && Y >= 0 && Y < a.length && X >= 0 && X < a[0].length) a[Y][X] = ch;
  }));
  return fromArr(a);
}

// Pixel-level edits: list of [x, y, ch].
function plot(g, pts) {
  const a = toArr(g);
  for (const [x, y, ch] of pts) if (y >= 0 && y < a.length && x >= 0 && x < a[0].length) a[y][x] = ch;
  return fromArr(a);
}

function crop(g, x, y, w, h) { return g.slice(y, y + h).map(r => r.slice(x, x + w)); }
function flipX(g) { return g.map(r => [...r].reverse().join('')); }
function shift(g, dx, dy) { return paste(blank(W(g), Hh(g)), g, dx, dy); }
function pad(g, w, h, dx = 0, dy = 0) { return paste(blank(w, h), g, dx, dy); }

// Fill an ellipse (cx,cy,rx,ry) with ch.
function ellipse(g, cx, cy, rx, ry, ch) {
  const pts = [];
  for (let y = Math.floor(cy - ry); y <= Math.ceil(cy + ry); y++)
    for (let x = Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
      const nx = (x - cx) / (rx + 0.35), ny = (y - cy) / (ry + 0.35);
      if (nx * nx + ny * ny <= 1) pts.push([x, y, ch]);
    }
  return plot(g, pts);
}
function rect(g, x, y, w, h, ch) {
  const pts = [];
  for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) pts.push([x + i, y + j, ch]);
  return plot(g, pts);
}

// Add a 1px outline (4-neighbour) around all non-transparent pixels.
function outline(g, ch = 'o') {
  const pts = [];
  for (let y = 0; y < g.length; y++) for (let x = 0; x < g[0].length; x++) {
    if (g[y][x] !== '.') continue;
    if ([[1, 0], [-1, 0], [0, 1], [0, -1]].some(([a, b]) => get(g, x + a, y + b) !== '.' && get(g, x + a, y + b) !== ch))
      pts.push([x, y, ch]);
  }
  return plot(g, pts);
}

// Load a PNG region into a grid, snapping each colour to the nearest palette entry.
function loadGrid(file, x0, y0, w, h, allowed = 'ofsdcbkwpy') {
  const png = PNG.sync.read(fs.readFileSync(file));
  const rows = [];
  for (let y = y0; y < y0 + h; y++) {
    let row = '';
    for (let x = x0; x < x0 + w; x++) {
      const i = (png.width * y + x) * 4;
      if (png.data[i + 3] < 128) { row += '.'; continue; }
      const px = [png.data[i], png.data[i + 1], png.data[i + 2]];
      let best = '.', bd = Infinity;
      for (const k of allowed) {
        const d = RGB[k].reduce((s, v, j) => s + (v - px[j]) ** 2, 0);
        if (d < bd) { bd = d; best = k; }
      }
      row += best;
    }
    rows.push(row);
  }
  return rows;
}

// Write frames as a horizontal sheet PNG.
function writeSheet(file, frames, scale = 1, bg = null) {
  const fw = W(frames[0]), fh = Hh(frames[0]);
  const png = new PNG({ width: fw * frames.length * scale, height: fh * scale });
  if (bg) for (let i = 0; i < png.data.length; i += 4) { png.data[i] = bg[0]; png.data[i + 1] = bg[1]; png.data[i + 2] = bg[2]; png.data[i + 3] = 255; }
  frames.forEach((g, n) => g.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch === '.') return;
    const [r, gg, b] = RGB[ch];
    for (let sy = 0; sy < scale; sy++) for (let sx = 0; sx < scale; sx++) {
      const i = ((y * scale + sy) * png.width + (n * fw + x) * scale + sx) * 4;
      png.data[i] = r; png.data[i + 1] = gg; png.data[i + 2] = b; png.data[i + 3] = 255;
    }
  })));
  fs.writeFileSync(file, PNG.sync.write(png));
}

// Write an animated GIF preview (scaled, on a soft background).
function writeGif(file, frames, fps = 8, scale = 6, bg = [236, 238, 244]) {
  const { GIFEncoder, quantize, applyPalette } = require('gifenc');
  const fw = W(frames[0]) * scale, fh = Hh(frames[0]) * scale;
  const gif = GIFEncoder();
  for (const g of frames) {
    const data = new Uint8ClampedArray(fw * fh * 4);
    for (let y = 0; y < fh; y++) for (let x = 0; x < fw; x++) {
      const ch = g[Math.floor(y / scale)][Math.floor(x / scale)];
      const [r, gg, b] = ch === '.' ? bg : RGB[ch];
      const i = (y * fw + x) * 4;
      data[i] = r; data[i + 1] = gg; data[i + 2] = b; data[i + 3] = 255;
    }
    const pal = quantize(data, 64);
    gif.writeFrame(applyPalette(data, pal), fw, fh, { palette: pal, delay: Math.round(1000 / fps) });
  }
  gif.finish();
  fs.writeFileSync(file, gif.bytes());
}

module.exports = { PALETTE, RGB, blank, paste, plot, crop, flipX, shift, pad, ellipse, rect, outline, loadGrid, writeSheet, writeGif, W, H: Hh, get };

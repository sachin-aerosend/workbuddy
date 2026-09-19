// Dump a sprite frame as a character grid with its palette, for studying the art.
// usage: node tools/dump.js <png> <frameIndex> [frameW] [frameH] [rowY]
const fs = require('fs');
const { PNG } = require('pngjs');
const [file, idx = 0, fw = 32, fh = 32, ry = 0] = process.argv.slice(2);
const png = PNG.sync.read(fs.readFileSync(file));
const chars = '.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const pal = new Map();
const lines = [];
for (let y = +ry; y < +ry + +fh; y++) {
  let line = '';
  for (let x = idx * fw; x < idx * fw + +fw; x++) {
    const i = (png.width * y + x) * 4;
    const [r, g, b, a] = png.data.slice(i, i + 4);
    if (a < 128) { line += '.'; continue; }
    const hex = [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('');
    if (!pal.has(hex)) pal.set(hex, chars[pal.size + 1]);
    line += pal.get(hex);
  }
  lines.push(line);
}
console.log(`${png.width}x${png.height}`);
console.log([...pal].map(([h, c]) => `${c}=#${h}`).join(' '));
console.log(lines.join('\n'));

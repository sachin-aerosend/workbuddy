// Builds assets/icon.ico (16–256px, PNG-compressed entries) and icon.png from the cat's face.
const fs = require('fs');
const path = require('path');
const { PNG } = require('pngjs');

const ROOT = path.join(__dirname, '..');
const face = PNG.sync.read(fs.readFileSync(path.join(ROOT, 'assets', 'icon-face.png')));

function render(size) {
  const out = new PNG({ width: size, height: size });
  const k = Math.max(1, Math.floor(size / face.width));        // whole-pixel scale when possible
  const span = size >= face.width ? face.width * k : size;
  const off = Math.floor((size - span) / 2);
  for (let y = 0; y < span; y++) for (let x = 0; x < span; x++) {
    const sx = Math.floor(x * face.width / span), sy = Math.floor(y * face.height / span);
    const si = (sy * face.width + sx) * 4, di = ((y + off) * size + (x + off)) * 4;
    face.data.copy(out.data, di, si, si + 4);
  }
  return PNG.sync.write(out);
}

const sizes = [16, 24, 32, 48, 64, 128, 256];
const pngs = sizes.map(render);
const header = Buffer.alloc(6 + 16 * sizes.length);
header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(sizes.length, 4);
let offset = header.length;
sizes.forEach((s, i) => {
  const e = 6 + 16 * i;
  header.writeUInt8(s === 256 ? 0 : s, e); header.writeUInt8(s === 256 ? 0 : s, e + 1);
  header.writeUInt16LE(1, e + 4); header.writeUInt16LE(32, e + 6);
  header.writeUInt32LE(pngs[i].length, e + 8); header.writeUInt32LE(offset, e + 12);
  offset += pngs[i].length;
});
fs.writeFileSync(path.join(ROOT, 'assets', 'icon.ico'), Buffer.concat([header, ...pngs]));
fs.writeFileSync(path.join(ROOT, 'assets', 'icon.png'), pngs[sizes.indexOf(256)]);
for (const s of [16, 48, 128]) fs.writeFileSync(path.join(ROOT, 'extension', `icon${s}.png`), render(s));
console.log('icon built');

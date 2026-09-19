// Android launcher icons from the cat face: cream rounded tile + pixel face, every density.
const fs = require('fs');
const path = require('path');
const { PNG } = require('pngjs');

const ROOT = path.join(__dirname, '..');
const face = PNG.sync.read(fs.readFileSync(path.join(ROOT, 'assets', 'icon-face.png')));
const RES = path.join(ROOT, 'android', 'app', 'src', 'main', 'res');
const SIZES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const CREAM = [255, 244, 230], INK = [42, 35, 32];

function icon(size, round) {
  const png = new PNG({ width: size, height: size });
  const r = round ? size / 2 : size * 0.22, border = Math.max(2, Math.round(size / 32));
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    // rounded-rect (or circle) mask with a thin ink border
    const cx = Math.min(Math.max(x + 0.5, r), size - r), cy = Math.min(Math.max(y + 0.5, r), size - r);
    const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
    const i = (y * size + x) * 4;
    if (d > r) { png.data[i + 3] = 0; continue; }
    const c = d > r - border ? INK : CREAM;
    png.data[i] = c[0]; png.data[i + 1] = c[1]; png.data[i + 2] = c[2]; png.data[i + 3] = 255;
  }
  // face, nearest-neighbour, ~66% of the tile
  const span = Math.round(size * 0.66), off = Math.round((size - span) / 2) + Math.round(size * 0.02);
  for (let y = 0; y < span; y++) for (let x = 0; x < span; x++) {
    const sx = Math.floor(x * face.width / span), sy = Math.floor(y * face.height / span);
    const si = (sy * face.width + sx) * 4;
    if (face.data[si + 3] < 128) continue;
    const di = ((y + off) * size + (x + off - Math.round(size * 0.02))) * 4;
    face.data.copy(png.data, di, si, si + 4);
  }
  return PNG.sync.write(png);
}

for (const [dpi, s] of Object.entries(SIZES)) {
  const dir = path.join(RES, `mipmap-${dpi}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), icon(s, false));
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), icon(s, true));
}
fs.writeFileSync(path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'drawable', 'cat_face.png'), icon(192, false));
console.log('android icons built');

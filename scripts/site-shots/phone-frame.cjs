// A placeholder phone screen for the harness: the FORJA „Azi” panel drawn with flat rectangles into a PNG, with no
// image library (node:zlib only). The site shows whatever bytes the phone sends, so a PNG stands in for the JPEG.
const zlib = require('node:zlib');

function png(width, height, pixels) {
  const crc = buf => (zlib.crc32 ? zlib.crc32(buf) : crc32(buf)) >>> 0;
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const body = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(body)); return Buffer.concat([len, body, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) { raw[y * (width * 3 + 1)] = 0; pixels.copy(raw, y * (width * 3 + 1) + 1, y * width * 3, (y + 1) * width * 3); }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0))]);
}
let TABLE = null;
function crc32(buf) {
  if (!TABLE) { TABLE = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; TABLE[n] = c; } }
  let c = -1; for (const b of buf) c = TABLE[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0;
}
const hex = s => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];

/** 360×780 by default (the phone sends ~360 px wide frames). `variant` shifts the fake content a little between frames. */
function phoneFrame({ width = 360, height = 780, variant = 0 } = {}) {
  const px = Buffer.alloc(width * height * 3);
  const rect = (x, y, w, h, color) => { const [r, g, b] = hex(color); const x1 = Math.min(width, x + w), y1 = Math.min(height, y + h); for (let yy = Math.max(0, y); yy < y1; yy++) for (let xx = Math.max(0, x); xx < x1; xx++) { const i = (yy * width + xx) * 3; px[i] = r; px[i + 1] = g; px[i + 2] = b; } };
  const line = (x, y, w, color) => rect(x, y, w, 3, color);
  rect(0, 0, width, height, '#0A0A0B');
  rect(0, 0, width, 28, '#0A0A0B'); rect(12, 10, 34, 8, '#A7A9AE'); rect(width - 60, 10, 22, 8, '#A7A9AE'); rect(width - 32, 10, 20, 8, '#2FBE71'); // bara de sus
  rect(20, 48, 120, 14, '#6F855A'); line(20, 70, 180, '#7A7D83'); // ștampila + titlul
  rect(20, 92, width - 40, 150, '#121214'); rect(20, 92, width - 40, 1, '#2A2A30'); // cardul zilei
  rect(36, 108, 90, 10, '#7A7D83'); rect(36, 128, 150, 26, '#F4F2EE'); rect(36, 166, 110, 10, '#A7A9AE');
  rect(width - 120, 112, 84, 84, '#4A5D3A'); rect(width - 108, 124, 60, 60, '#F3B952'); // Casca
  const bars = [0.72, 0.4, 0.95, 0.55, 0.3, 0.8, 0.62]; // misiunile zilei
  for (let i = 0; i < bars.length; i++) { const w = Math.round((width - 72) * (((bars[i] * 7 + variant * 0.13) % 1) * 0.6 + 0.3)); rect(20, 262 + i * 46, width - 40, 36, '#121214'); rect(32, 276 + i * 46, w, 8, i % 3 === 0 ? '#F3B952' : '#6F855A'); rect(32, 266 + i * 46, 70, 6, '#7A7D83'); }
  rect(20, 592, width - 40, 90, '#17181C'); rect(36, 608, 140, 12, '#F4F2EE'); rect(36, 632, 220, 8, '#A7A9AE'); rect(36, 650, 180, 8, '#7A7D83');
  rect(0, height - 66, width, 66, '#0E0E10'); for (let i = 0; i < 7; i++) rect(14 + i * 48, height - 50, 26, 26, i === (variant % 7) ? '#F3B952' : '#5A5D63'); // bara de file
  return png(width, height, px);
}
module.exports = { phoneFrame, png };

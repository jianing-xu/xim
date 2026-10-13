import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// icon.svg is the source for every raster size. Uses the existing Playwright dependency.
const publicDir = fileURLToPath(new URL('../public/', import.meta.url));
await mkdir(publicDir, { recursive: true });
const svg = await readFile(`${publicDir}/icon.svg`, 'utf8');
const browser = await chromium.launch({ channel: process.env.XIM_BROWSER_CHANNEL || 'chrome' });
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  async function render(size, square = false) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<style>html,body{margin:0;width:100%;height:100%;background:${square ? '#3247C9' : 'transparent'}}svg{display:block;width:100%;height:100%}</style>${svg}`);
    return page.screenshot({ omitBackground: !square });
  }
  await writeFile(`${publicDir}/icon.png`, await render(512));
  // iOS applies its own corner mask, so this export has a fully opaque square background.
  await writeFile(`${publicDir}/apple-touch-icon.png`, await render(180, true));

  // ICO supports PNG entries; keep native 16, 32 and 48 px versions for crisp tabs.
  const sizes = [16, 32, 48];
  const pngs = [];
  for (const size of sizes) pngs.push(await render(size));
  const header = Buffer.alloc(6 + sizes.length * 16);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(sizes.length, 4);
  let offset = header.length;
  for (let i = 0; i < sizes.length; i++) {
    const entry = 6 + i * 16;
    header[entry] = sizes[i];
    header[entry + 1] = sizes[i];
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(pngs[i].length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += pngs[i].length;
  }
  await writeFile(`${publicDir}/favicon.ico`, Buffer.concat([header, ...pngs]));
  console.log('Generated icon.png (512), apple-touch-icon.png (180), favicon.ico (16/32/48).');
} finally {
  await browser.close();
}

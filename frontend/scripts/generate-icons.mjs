import {readFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import sharp from "sharp";

const publicDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../public");
const logoPath = path.join(publicDir, "logo.svg");

const BG = "#0e0d0c";
const SAFE_ZONE = 0.8;

let logo;
try {
    logo = await readFile(logoPath);
} catch {
    console.error("[generate-icons] frontend/public/logo.svg not found. PNG icons are generated at build time and are never committed — the logo SVG is the source of truth.");
    process.exit(1);
}

const raster = (size) => sharp(logo, {density: 300}).resize(size, size).png().toBuffer();

await raster(192).then((buf) => sharp(buf).toFile(path.join(publicDir, "icon-192.png")));
await raster(512).then((buf) => sharp(buf).toFile(path.join(publicDir, "icon-512.png")));
await raster(180).then((buf) => sharp(buf).toFile(path.join(publicDir, "apple-touch-icon.png")));

const maskInner = Math.round(512 * SAFE_ZONE);
const maskLogo = await raster(maskInner);
await sharp({create: {width: 512, height: 512, channels: 4, background: BG}})
    .composite([{input: maskLogo, left: (512 - maskInner) / 2, top: (512 - maskInner) / 2}])
    .png()
    .toFile(path.join(publicDir, "icon-maskable-512.png"));

console.log("[generate-icons] PNGs regenerated from frontend/public/logo.svg");
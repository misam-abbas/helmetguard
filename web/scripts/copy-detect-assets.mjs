// Puts what the in-browser detector needs into public/ before dev and build:
// the ONNX Runtime WebAssembly files, and the models.
//
// The models come from the Android app's folder when it is next to this one (a full
// checkout). A deployment that only has the web folder downloads them instead, from the
// GitHub commit pinned in models.lock.json, and checks each file's SHA-256 against it.
// After changing the models, run: node scripts/lock-models.mjs <commit that has them>
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const web = join(dirname(fileURLToPath(import.meta.url)), "..");
const ortDist = join(web, "node_modules", "onnxruntime-web", "dist");
const ortOut = join(web, "public", "ort");
const modelsIn = join(web, "..", "android", "app", "src", "main", "assets", "models");
const modelsOut = join(web, "public", "models");
const lockPath = join(web, "models.lock.json");

mkdirSync(ortOut, { recursive: true });
for (const name of ["ort-wasm-simd-threaded.wasm", "ort-wasm-simd-threaded.mjs"]) {
  copyFileSync(join(ortDist, name), join(ortOut, name));
}

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");

if (existsSync(join(modelsIn, "models.json"))) {
  mkdirSync(modelsOut, { recursive: true });
  for (const name of readdirSync(modelsIn).filter((n) => n.endsWith(".onnx") || n === "models.json")) {
    copyFileSync(join(modelsIn, name), join(modelsOut, name));
  }
} else if (existsSync(lockPath)) {
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  mkdirSync(modelsOut, { recursive: true });
  for (const [name, expected] of Object.entries(lock.files)) {
    const target = join(modelsOut, name);
    if (existsSync(target) && sha256(readFileSync(target)) === expected) continue;
    const response = await fetch(`${lock.source}${name}`);
    if (!response.ok) throw new Error(`Could not download ${name} for the detector (HTTP ${response.status}).`);
    const data = Buffer.from(await response.arrayBuffer());
    if (sha256(data) !== expected) throw new Error(`${name} does not match models.lock.json. Run scripts/lock-models.mjs again.`);
    writeFileSync(target, data);
    console.log(`Downloaded ${name} (${(data.length / 1e6).toFixed(1)} MB)`);
  }
} else if (!existsSync(join(modelsOut, "models.json"))) {
  console.warn("No detection models found. The Analyse video page will say they are missing.");
}

// Writes models.lock.json: where a web-only deployment downloads the detector models
// from, and their SHA-256, so it gets exactly the models in the Android folder.
//
//   node scripts/lock-models.mjs [commit]     (default: the current commit)
//
// The commit must contain the models and be pushed to GitHub.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const web = join(dirname(fileURLToPath(import.meta.url)), "..");
const modelsIn = join(web, "..", "android", "app", "src", "main", "assets", "models");
const commit = process.argv[2] ?? execFileSync("git", ["rev-parse", "HEAD"], { cwd: web, encoding: "utf8" }).trim();
if (!/^[0-9a-f]{40}$/.test(commit)) throw new Error(`Not a full commit hash: ${commit}`);

const files = {};
for (const name of readdirSync(modelsIn).filter((n) => n.endsWith(".onnx") || n === "models.json").sort()) {
  const committed = execFileSync("git", ["show", `${commit}:android/app/src/main/assets/models/${name}`], { cwd: web, maxBuffer: 1 << 28 });
  const local = readFileSync(join(modelsIn, name));
  if (!committed.equals(local)) throw new Error(`${name} in commit ${commit} differs from the file on disk. Commit it first.`);
  files[name] = createHash("sha256").update(local).digest("hex");
}

const lock = {
  source: `https://raw.githubusercontent.com/misam-abbas/helmetguard/${commit}/android/app/src/main/assets/models/`,
  files,
};
writeFileSync(join(web, "models.lock.json"), JSON.stringify(lock, null, 2) + "\n");
console.log(`models.lock.json now points at ${commit}`);

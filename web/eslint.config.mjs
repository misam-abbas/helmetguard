import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  // public/ort holds ONNX Runtime's own minified files, copied in before dev and build.
  globalIgnores([".next/**", "next-env.d.ts", "public/ort/**", "public/models/**"]),
]);

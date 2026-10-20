#!/usr/bin/env node
// Generates one TypeScript file per backend contract into src/api/generated/, then the committed
// src/api/schema.ts barrel re-exports all of them as one surface.
//
// Why seven separate invocations instead of one: openapi-typescript's CLI takes exactly one
// `[input]` document per run (confirmed via `openapi-typescript --help` — no multi-file or glob
// merge exists). The seven backend contracts are independent OpenAPI documents (separate `info`,
// separate `servers`), not fragments of one combined document, and some even reuse the same schema
// name (`ProblemDetail`) for the same shared shape — bundling them into a single document first
// would need a bundler tool this project does not otherwise need. Generating one file per contract
// and re-exporting them under per-service namespaces avoids both problems and still gives the app
// exactly one file to import types from (research.md R3; constitution XIII).
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const outDir = join(here, "..", "src", "api", "generated");

// [namespace, contract path relative to repo root]
const contracts = [
  ["auth", "specs/002-user-auth/contracts/auth-service.openapi.yaml"],
  ["course", "specs/003-course-catalog/contracts/course-service.openapi.yaml"],
  ["courseContent", "specs/004-checkout-enrollment/contracts/course-service-content.openapi.yaml"],
  ["payment", "specs/004-checkout-enrollment/contracts/payment-service.openapi.yaml"],
  ["enrollment", "specs/004-checkout-enrollment/contracts/enrollment-service.openapi.yaml"],
  ["progress", "specs/005-progress-certificates-notifications/contracts/enrollment-service-progress.openapi.yaml"],
  ["certificate", "specs/005-progress-certificates-notifications/contracts/certificate-service.openapi.yaml"],
];

for (const [namespace, relativePath] of contracts) {
  const input = join(root, relativePath);
  const output = join(outDir, `${namespace}.ts`);
  console.log(`Generating ${namespace} <- ${relativePath}`);
  execFileSync(
    "npx",
    ["--yes", "openapi-typescript", input, "--output", output],
    { stdio: "inherit", shell: true },
  );
}

const barrelLines = [
  "// GENERATED ENTRY POINT. The files under ./generated/ are produced by `npm run gen:api`",
  "// (frontend/scripts/gen-api.mjs) from the backend's own OpenAPI contracts; this barrel is",
  "// hand-written and stable so the rest of the app has one import surface regardless of how many",
  "// contracts back it (research.md R3).",
  ...contracts.map(([namespace]) => `export * as ${namespace} from "./generated/${namespace}";`),
  "",
];

const fs = await import("node:fs/promises");
await fs.mkdir(join(here, "..", "src", "api"), { recursive: true });
await fs.writeFile(join(here, "..", "src", "api", "schema.ts"), barrelLines.join("\n"));
console.log("Wrote src/api/schema.ts");

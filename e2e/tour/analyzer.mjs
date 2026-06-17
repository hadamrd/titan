// Tour analyzer — runs each rubric item per page through the configured
// vision provider, writes findings.json + diff.md, exits non-zero if any
// NEW failure relative to baseline (warns-only is exit 0).
//
// CLI:
//   node analyzer.mjs analyze   # screenshots → findings.json
//   node analyzer.mjs diff      # findings.json → diff.md (re-uses cached findings)
//   node analyzer.mjs run       # analyze + diff (default for task tour:analyze)

import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadRubric } from "./rubric-loader.mjs";
import { selectProvider } from "./providers/index.mjs";
import { SCHEMA_VERSION, validateFindingsDoc } from "./schema.mjs";
import { diffFindings, hasNewFails, renderDiffMarkdown } from "./diff.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));

export const DEFAULTS = {
  rubricPath: join(__dirname, "rubric.yaml"),
  screenshotsDir: join(__dirname, "current"),
  outFindings: join(__dirname, "current", "findings.json"),
  outDiff: join(__dirname, "current", "diff.md"),
  baseline: join(__dirname, "baseline", "findings.json"),
};

export function pageSlug(p, viewport) {
  return `${p.replace(/^\//, "").replace(/[^a-z0-9]/gi, "_") || "root"}--${viewport}.png`;
}

export async function analyze({
  rubricPath,
  screenshotsDir,
  outFindings,
  env = process.env,
  logger = console,
  provider = null,
  now = () => new Date().toISOString(),
} = {}) {
  rubricPath ||= DEFAULTS.rubricPath;
  screenshotsDir ||= DEFAULTS.screenshotsDir;
  outFindings ||= DEFAULTS.outFindings;
  const rubric = loadRubric(rubricPath);
  provider ||= selectProvider({ env, logger });
  const findings = [];
  for (const page of rubric.pages) {
    const shot = join(screenshotsDir, pageSlug(page.path, page.viewport));
    const shotExists = existsSync(shot);
    for (const item of page.items) {
      const rubricItem = rubric.items.get(item);
      if (!shotExists) {
        // Sad path: missing screenshot → record as error, don't crash.
        findings.push({
          page: page.path,
          viewport: page.viewport,
          rubric_item: item,
          verdict: "error",
          evidence: `screenshot not found at ${shot}`,
          screenshot: shot,
        });
        continue;
      }
      try {
        const f = await provider.analyze({
          page: page.path,
          viewport: page.viewport,
          rubricItem: item,
          rubricPrompt: rubricItem.prompt,
          screenshotPath: shot,
        });
        findings.push(f);
      } catch (e) {
        logger.error(`[tour] analyzer threw on ${page.path}@${page.viewport}:${item}: ${e.message}`);
        findings.push({
          page: page.path,
          viewport: page.viewport,
          rubric_item: item,
          verdict: "error",
          evidence: `analyzer threw: ${e.message}`,
          screenshot: shot,
        });
      }
    }
  }
  const doc = {
    schema_version: SCHEMA_VERSION,
    generated_at: now(),
    provider: provider.name,
    findings,
  };
  validateFindingsDoc(doc, rubric.knownItemNames);
  mkdirSync(dirname(outFindings), { recursive: true });
  // Deterministic write: stable key order + trailing newline.
  writeFileSync(outFindings, JSON.stringify(doc, null, 2) + "\n");
  return doc;
}

export function runDiff({
  baselinePath,
  currentPath,
  outDiff,
  rubric = null,
  logger = console,
} = {}) {
  baselinePath ||= DEFAULTS.baseline;
  currentPath ||= DEFAULTS.outFindings;
  outDiff ||= DEFAULTS.outDiff;
  if (!existsSync(currentPath)) {
    throw new Error(`current findings not found at ${currentPath} — run tour:analyze first`);
  }
  const current = JSON.parse(readFileSync(currentPath, "utf8"));
  const known = rubric ? rubric.knownItemNames : null;
  validateFindingsDoc(current, known);
  let baseline;
  if (existsSync(baselinePath)) {
    baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
    validateFindingsDoc(baseline, known);
  } else {
    logger.warn(`[tour] no baseline at ${baselinePath} — treating empty baseline`);
    baseline = { schema_version: SCHEMA_VERSION, findings: [] };
  }
  const d = diffFindings(baseline, current);
  const md = renderDiffMarkdown(d);
  mkdirSync(dirname(outDiff), { recursive: true });
  writeFileSync(outDiff, md);
  return { diff: d, markdown: md };
}

async function main(argv) {
  const cmd = argv[2] || "run";
  if (!["analyze", "diff", "run"].includes(cmd)) {
    console.error(`usage: analyzer.mjs <analyze|diff|run>`);
    process.exit(2);
  }
  const rubric = loadRubric(DEFAULTS.rubricPath);
  if (cmd === "analyze" || cmd === "run") {
    await analyze({});
  }
  if (cmd === "diff" || cmd === "run") {
    const { diff } = runDiff({ rubric });
    if (hasNewFails(diff)) {
      console.error(
        `[tour] ${diff.regressions.length} regression(s), including new fail(s) — gating`
      );
      process.exit(1);
    }
    console.log(
      `[tour] OK — ${diff.regressions.length} warn-level regression(s), ${diff.resolutions.length} resolution(s)`
    );
  }
}

if (resolve(fileURLToPath(import.meta.url)) === resolve(process.argv[1] || "")) {
  main(process.argv).catch((e) => {
    console.error(`[tour] fatal: ${e.message}`);
    process.exit(2);
  });
}

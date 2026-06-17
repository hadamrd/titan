import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { analyze, runDiff, pageSlug } from "../analyzer.mjs";
import { loadRubric } from "../rubric-loader.mjs";

describe("analyzer end-to-end with mock provider", () => {
  it("writes findings.json + diff.md against the committed fixture screenshots", async () => {
    const work = mkdtempSync(join(tmpdir(), "tour-it-"));
    const outFindings = join(work, "current", "findings.json");
    const outDiff = join(work, "current", "diff.md");
    const doc = await analyze({
      rubricPath: "tour/rubric.yaml",
      screenshotsDir: "tour/fixtures/screenshots",
      outFindings,
      env: {},
      logger: { warn() {}, error() {}, log() {} },
      now: () => "2026-01-01T00:00:00.000Z",
    });
    assert.equal(doc.schema_version, 1);
    assert.equal(doc.provider, "mock");
    assert.ok(doc.findings.length > 0);
    assert.ok(existsSync(outFindings));

    const rubric = loadRubric("tour/rubric.yaml");
    const { diff } = runDiff({
      baselinePath: "tour/baseline/findings.json",
      currentPath: outFindings,
      outDiff,
      rubric,
      logger: { warn() {}, error() {}, log() {} },
    });
    assert.ok(existsSync(outDiff));
    // With identical baseline, diff is empty.
    assert.equal(diff.regressions.length, 0);
    assert.equal(diff.resolutions.length, 0);
    const md = readFileSync(outDiff, "utf8");
    assert.match(md, /No new regressions/);
  });

  it("produces byte-identical findings.json across two runs (mock determinism)", async () => {
    const work = mkdtempSync(join(tmpdir(), "tour-det-"));
    const out1 = join(work, "a.json");
    const out2 = join(work, "b.json");
    const opts = {
      rubricPath: "tour/rubric.yaml",
      screenshotsDir: "tour/fixtures/screenshots",
      env: {},
      logger: { warn() {}, error() {}, log() {} },
      now: () => "2026-01-01T00:00:00.000Z",
    };
    await analyze({ ...opts, outFindings: out1 });
    await analyze({ ...opts, outFindings: out2 });
    assert.equal(readFileSync(out1, "utf8"), readFileSync(out2, "utf8"));
  });

  it("emits verdict:error for a missing screenshot (does NOT crash)", async () => {
    const work = mkdtempSync(join(tmpdir(), "tour-miss-"));
    const outFindings = join(work, "findings.json");
    // Empty dir → no screenshots present.
    const doc = await analyze({
      rubricPath: "tour/rubric.yaml",
      screenshotsDir: work,
      outFindings,
      env: {},
      logger: { warn() {}, error() {}, log() {} },
      now: () => "2026-01-01T00:00:00.000Z",
    });
    assert.ok(doc.findings.every((f) => f.verdict === "error"));
    assert.ok(doc.findings.every((f) => /not found/.test(f.evidence)));
  });

  it("pageSlug round-trips a /jobs route to a stable filename", () => {
    assert.equal(pageSlug("/jobs", "desktop"), "jobs--desktop.png");
    assert.equal(pageSlug("/", "mobile"), "root--mobile.png");
    assert.equal(pageSlug("/404", "desktop"), "404--desktop.png");
  });

  it("flags a NEW fail and signals gating (hasNewFails path)", async () => {
    // Re-use mock findings as baseline, then inject a fail and diff.
    const work = mkdtempSync(join(tmpdir(), "tour-gate-"));
    const baselinePath = join(work, "baseline.json");
    const currentPath = join(work, "current.json");
    const outDiff = join(work, "diff.md");
    const base = JSON.parse(
      readFileSync("tour/baseline/findings.json", "utf8")
    );
    // Drop any existing fails in baseline so the synthesized fail is "new"
    base.findings = base.findings.filter((f) => f.verdict !== "fail");
    writeFileSync(baselinePath, JSON.stringify(base, null, 2) + "\n");
    const cur = JSON.parse(JSON.stringify(base));
    cur.findings.push({
      page: "/jobs",
      viewport: "desktop",
      rubric_item: "empty_state_present",
      verdict: "fail",
      evidence: "no empty state",
      screenshot: "x.png",
    });
    writeFileSync(currentPath, JSON.stringify(cur, null, 2) + "\n");
    const rubric = loadRubric("tour/rubric.yaml");
    const { diff } = runDiff({
      baselinePath,
      currentPath,
      outDiff,
      rubric,
      logger: { warn() {}, error() {}, log() {} },
    });
    const { hasNewFails } = await import("../diff.mjs");
    assert.equal(hasNewFails(diff), true);
  });
});

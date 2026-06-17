import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { diffFindings, hasNewFails, renderDiffMarkdown } from "../diff.mjs";

const f = (page, viewport, item, verdict, extra = "") => ({
  page,
  viewport,
  rubric_item: item,
  verdict,
  evidence: `${verdict}${extra}`,
  screenshot: `current/${page.slice(1)}--${viewport}.png`,
});
const doc = (...findings) => ({ schema_version: 1, findings });

describe("diff", () => {
  it("returns empty when baseline == current", () => {
    const a = doc(f("/x", "desktop", "i", "pass"));
    const d = diffFindings(a, a);
    assert.equal(d.regressions.length, 0);
    assert.equal(d.resolutions.length, 0);
    assert.equal(hasNewFails(d), false);
  });

  it("flags a new fail not in baseline", () => {
    const base = doc(f("/x", "desktop", "i", "pass"));
    const cur = doc(
      f("/x", "desktop", "i", "pass"),
      f("/x", "desktop", "j", "fail")
    );
    const d = diffFindings(base, cur);
    assert.equal(d.regressions.length, 1);
    assert.equal(d.regressions[0].kind, "new");
    assert.equal(hasNewFails(d), true);
  });

  it("flags a warn→fail escalation as regression+new-fail", () => {
    const base = doc(f("/x", "desktop", "i", "warn"));
    const cur = doc(f("/x", "desktop", "i", "fail"));
    const d = diffFindings(base, cur);
    assert.equal(d.regressions.length, 1);
    assert.equal(d.regressions[0].kind, "escalated");
    assert.equal(hasNewFails(d), true);
  });

  it("flags a pass→warn escalation as regression but NOT a new-fail gate", () => {
    const base = doc(f("/x", "desktop", "i", "pass"));
    const cur = doc(f("/x", "desktop", "i", "warn"));
    const d = diffFindings(base, cur);
    assert.equal(d.regressions.length, 1);
    assert.equal(hasNewFails(d), false);
  });

  it("records a resolved fail (fail→pass)", () => {
    const base = doc(f("/x", "desktop", "i", "fail"));
    const cur = doc(f("/x", "desktop", "i", "pass"));
    const d = diffFindings(base, cur);
    assert.equal(d.resolutions.length, 1);
    assert.equal(d.resolutions[0].kind, "resolved");
    assert.equal(hasNewFails(d), false);
  });

  it("records a removed fail when the cell vanishes", () => {
    const base = doc(f("/x", "desktop", "i", "fail"));
    const cur = doc();
    const d = diffFindings(base, cur);
    assert.equal(d.resolutions.length, 1);
    assert.equal(d.resolutions[0].kind, "removed");
  });

  it("renders a Markdown summary that names the regression", () => {
    const base = doc(f("/x", "desktop", "i", "pass"));
    const cur = doc(f("/x", "desktop", "i", "fail"));
    const d = diffFindings(base, cur);
    const md = renderDiffMarkdown(d);
    assert.match(md, /Regressions/);
    assert.match(md, /\/x/);
    assert.match(md, /FAIL/);
  });

  it("renders an empty-diff summary when nothing changed", () => {
    const md = renderDiffMarkdown({ regressions: [], resolutions: [] });
    assert.match(md, /No new regressions/);
  });
});

// Diff — baseline vs current findings, surfaces regressions + resolutions.
//
// Key per finding: page|viewport|rubric_item (one verdict per cell).
// Regression = verdict got worse OR new fail/warn that wasn't in baseline.
// Resolution = a fail in baseline became pass/warn (or disappeared).
//
// We return a structured object the renderer can format as Markdown.

const RANK = { pass: 0, warn: 1, fail: 2, error: 2 };

function keyOf(f) {
  return `${f.page}|${f.viewport}|${f.rubric_item}`;
}

export function diffFindings(baseline, current) {
  const baseMap = new Map(baseline.findings.map((f) => [keyOf(f), f]));
  const currMap = new Map(current.findings.map((f) => [keyOf(f), f]));
  const regressions = [];
  const resolutions = [];
  for (const [k, cur] of currMap) {
    const prev = baseMap.get(k);
    if (!prev) {
      if (cur.verdict === "fail" || cur.verdict === "warn" || cur.verdict === "error") {
        regressions.push({ kind: "new", current: cur, baseline: null });
      }
      continue;
    }
    if (RANK[cur.verdict] > RANK[prev.verdict]) {
      regressions.push({ kind: "escalated", current: cur, baseline: prev });
    } else if (RANK[cur.verdict] < RANK[prev.verdict] && prev.verdict === "fail") {
      resolutions.push({ kind: "resolved", current: cur, baseline: prev });
    }
  }
  for (const [k, prev] of baseMap) {
    if (!currMap.has(k) && prev.verdict === "fail") {
      resolutions.push({ kind: "removed", current: null, baseline: prev });
    }
  }
  return { regressions, resolutions };
}

export function hasNewFails(diff) {
  return diff.regressions.some(
    (r) =>
      r.current && r.current.verdict === "fail" &&
      (r.kind === "new" || (r.baseline && r.baseline.verdict !== "fail"))
  );
}

export function renderDiffMarkdown(diff, { title = "Tour analysis" } = {}) {
  const lines = [`# ${title} — diff vs baseline`, ""];
  if (diff.regressions.length === 0 && diff.resolutions.length === 0) {
    lines.push("_No new regressions, no resolutions vs baseline._");
    return lines.join("\n") + "\n";
  }
  if (diff.regressions.length > 0) {
    lines.push(`## Regressions (${diff.regressions.length})`, "");
    for (const r of diff.regressions) {
      const c = r.current;
      const was = r.baseline ? r.baseline.verdict : "absent";
      lines.push(
        `- **${c.verdict.toUpperCase()}** \`${c.page}\` @ ${c.viewport} — ` +
          `*${c.rubric_item}* (was: ${was})`
      );
      lines.push(`  > ${c.evidence}`);
      lines.push(`  - screenshot: \`${c.screenshot}\``);
    }
    lines.push("");
  }
  if (diff.resolutions.length > 0) {
    lines.push(`## Resolutions (${diff.resolutions.length})`, "");
    for (const r of diff.resolutions) {
      const ref = r.current || r.baseline;
      const now = r.current ? r.current.verdict : "removed";
      lines.push(
        `- \`${ref.page}\` @ ${ref.viewport} — *${ref.rubric_item}* ` +
          `(fail → ${now})`
      );
    }
    lines.push("");
  }
  return lines.join("\n");
}

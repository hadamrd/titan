// Mock vision provider — deterministic findings keyed off the screenshot
// path + rubric item. Same inputs → byte-identical findings.json. Used in
// CI and as the offline fallback when no Anthropic key is present.
//
// Determinism is enforced by a stable FNV-1a hash of (page|viewport|item).
// We DO NOT read pixel bytes — the mock is a logic-stub, not a sham vision
// model. Byte-identical-across-100-runs is a test invariant.

const VERDICT_CYCLE = ["pass", "pass", "warn", "pass", "fail", "pass", "warn"];

function fnv1a(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
  }
  return h >>> 0;
}

export function createMockProvider() {
  return {
    name: "mock",
    async analyze({ page, viewport, rubricItem, screenshotPath }) {
      const key = `${page}|${viewport}|${rubricItem}`;
      const h = fnv1a(key);
      const verdict = VERDICT_CYCLE[h % VERDICT_CYCLE.length];
      // Quote a stable canned phrase so determinism tests pass.
      const evidence = `[mock] ${rubricItem} on ${page}@${viewport} → ${verdict}`;
      return {
        page,
        viewport,
        rubric_item: rubricItem,
        verdict,
        evidence,
        screenshot: screenshotPath,
      };
    },
  };
}

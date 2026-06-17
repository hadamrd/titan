import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  validateFinding,
  validateFindingsDoc,
  SchemaError,
  SCHEMA_VERSION,
} from "../schema.mjs";

const good = {
  page: "/jobs",
  viewport: "desktop",
  rubric_item: "empty_state_present",
  verdict: "pass",
  evidence: "looks fine",
  screenshot: "current/jobs--desktop.png",
};
const knownItems = new Set(["empty_state_present"]);

describe("finding schema", () => {
  it("accepts a well-formed finding", () => {
    assert.equal(validateFinding(good, knownItems, 0), true);
  });

  it("rejects a finding missing verdict", () => {
    const bad = { ...good };
    delete bad.verdict;
    assert.throws(
      () => validateFinding(bad, knownItems, 0),
      (e) => e instanceof SchemaError && /missing key 'verdict'/.test(e.message)
    );
  });

  it("rejects an unknown rubric_item", () => {
    assert.throws(
      () => validateFinding({ ...good, rubric_item: "made_up" }, knownItems, 0),
      (e) => e instanceof SchemaError && /unknown rubric item 'made_up'/.test(e.message)
    );
  });

  it("rejects a non-route page", () => {
    assert.throws(
      () => validateFinding({ ...good, page: "jobs" }, knownItems, 0),
      SchemaError
    );
  });

  it("rejects an invalid verdict literal", () => {
    assert.throws(
      () => validateFinding({ ...good, verdict: "yeah" }, knownItems, 0),
      SchemaError
    );
  });

  it("validates a full findings doc", () => {
    const doc = {
      schema_version: SCHEMA_VERSION,
      generated_at: "2026-01-01T00:00:00Z",
      findings: [good],
    };
    assert.equal(validateFindingsDoc(doc, knownItems), true);
  });

  it("rejects a doc with wrong schema_version", () => {
    assert.throws(
      () =>
        validateFindingsDoc(
          { schema_version: 99, findings: [] },
          knownItems
        ),
      SchemaError
    );
  });
});

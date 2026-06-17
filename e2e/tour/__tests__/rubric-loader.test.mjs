import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseRubric, RubricError } from "../rubric-loader.mjs";

describe("rubric loader", () => {
  const goodMin = {
    schema_version: 1,
    rubric_items: { foo: { prompt: "is foo present?" } },
    pages: [{ path: "/x", viewport: "desktop", items: ["foo"] }],
  };

  it("accepts a valid minimal rubric", () => {
    const r = parseRubric(goodMin);
    assert.equal(r.pages.length, 1);
    assert.ok(r.items.get("foo"));
    assert.deepEqual([...r.knownItemNames], ["foo"]);
  });

  it("rejects malformed schema_version", () => {
    assert.throws(
      () => parseRubric({ ...goodMin, schema_version: 99 }),
      (e) => e instanceof RubricError && /schema_version/.test(e.message)
    );
  });

  it("rejects an empty prompt", () => {
    assert.throws(
      () => parseRubric({ ...goodMin, rubric_items: { foo: { prompt: "  " } } }),
      RubricError
    );
  });

  it("rejects a page referencing an unknown rubric item", () => {
    assert.throws(
      () =>
        parseRubric({
          ...goodMin,
          pages: [{ path: "/x", viewport: "desktop", items: ["nope"] }],
        }),
      (e) => e instanceof RubricError && /unknown rubric item 'nope'/.test(e.message)
    );
  });

  it("rejects a non-route page.path", () => {
    assert.throws(
      () =>
        parseRubric({
          ...goodMin,
          pages: [{ path: "x", viewport: "desktop", items: ["foo"] }],
        }),
      RubricError
    );
  });

  it("rejects empty pages array", () => {
    assert.throws(
      () => parseRubric({ ...goodMin, pages: [] }),
      RubricError
    );
  });

  it("rejects non-object root", () => {
    assert.throws(() => parseRubric("nope"), RubricError);
  });
});

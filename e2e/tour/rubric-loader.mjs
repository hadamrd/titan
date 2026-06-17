// Rubric loader — reads rubric.yaml, validates structure, returns a typed view.
//
// Errors carry the offending path so the operator can fix the YAML.

import { readFileSync } from "node:fs";
import yaml from "js-yaml";

export class RubricError extends Error {
  constructor(message, { path } = {}) {
    super(message);
    this.name = "RubricError";
    this.path = path;
  }
}

export function loadRubric(rubricPath) {
  let raw;
  try {
    raw = readFileSync(rubricPath, "utf8");
  } catch (e) {
    throw new RubricError(`cannot read rubric at ${rubricPath}: ${e.message}`);
  }
  let parsed;
  try {
    parsed = yaml.load(raw);
  } catch (e) {
    throw new RubricError(`rubric YAML is malformed: ${e.message}`);
  }
  return parseRubric(parsed);
}

export function parseRubric(parsed) {
  if (parsed === null || typeof parsed !== "object") {
    throw new RubricError("rubric root must be an object");
  }
  if (parsed.schema_version !== 1) {
    throw new RubricError(
      `unsupported rubric schema_version: ${parsed.schema_version}`,
      { path: "schema_version" }
    );
  }
  if (!parsed.rubric_items || typeof parsed.rubric_items !== "object") {
    throw new RubricError("rubric_items must be an object", { path: "rubric_items" });
  }
  const items = new Map();
  for (const [name, body] of Object.entries(parsed.rubric_items)) {
    if (!body || typeof body.prompt !== "string" || body.prompt.trim() === "") {
      throw new RubricError(
        `rubric_items.${name}.prompt must be a non-empty string`,
        { path: `rubric_items.${name}.prompt` }
      );
    }
    items.set(name, { name, prompt: body.prompt.trim() });
  }
  if (!Array.isArray(parsed.pages) || parsed.pages.length === 0) {
    throw new RubricError("pages must be a non-empty array", { path: "pages" });
  }
  const pages = parsed.pages.map((p, i) => {
    if (!p || typeof p.path !== "string" || !p.path.startsWith("/")) {
      throw new RubricError("page.path must be a route", { path: `pages[${i}].path` });
    }
    if (typeof p.viewport !== "string") {
      throw new RubricError("page.viewport must be a string", {
        path: `pages[${i}].viewport`,
      });
    }
    if (!Array.isArray(p.items) || p.items.length === 0) {
      throw new RubricError("page.items must be a non-empty array", {
        path: `pages[${i}].items`,
      });
    }
    for (const it of p.items) {
      if (!items.has(it)) {
        throw new RubricError(
          `page references unknown rubric item '${it}'`,
          { path: `pages[${i}].items` }
        );
      }
    }
    return { path: p.path, viewport: p.viewport, items: [...p.items] };
  });
  return {
    schema_version: parsed.schema_version,
    items,
    pages,
    knownItemNames: new Set(items.keys()),
  };
}

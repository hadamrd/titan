// findings.json schema (v1) — validators with typed errors, no deps.
//
// Schema:
//   { schema_version: 1, generated_at: ISO-8601, findings: Finding[] }
// Finding:
//   { page, viewport, rubric_item, verdict, evidence, screenshot }
// verdict ∈ {pass, warn, fail, error}

export const SCHEMA_VERSION = 1;

export const VERDICTS = Object.freeze(["pass", "warn", "fail", "error"]);

export class SchemaError extends Error {
  constructor(message, { path } = {}) {
    super(message);
    this.name = "SchemaError";
    this.path = path;
  }
}

export function validateFinding(f, knownItems, idx = -1) {
  const at = (k) => `findings[${idx}].${k}`;
  if (f === null || typeof f !== "object") {
    throw new SchemaError("finding is not an object", { path: `findings[${idx}]` });
  }
  for (const k of ["page", "viewport", "rubric_item", "verdict", "evidence", "screenshot"]) {
    if (!(k in f)) throw new SchemaError(`missing key '${k}'`, { path: at(k) });
  }
  if (typeof f.page !== "string" || !f.page.startsWith("/")) {
    throw new SchemaError("page must be a route starting with '/'", { path: at("page") });
  }
  if (typeof f.viewport !== "string") {
    throw new SchemaError("viewport must be a string", { path: at("viewport") });
  }
  if (!VERDICTS.includes(f.verdict)) {
    throw new SchemaError(
      `verdict must be one of ${VERDICTS.join("|")}, got '${f.verdict}'`,
      { path: at("verdict") }
    );
  }
  if (typeof f.rubric_item !== "string") {
    throw new SchemaError("rubric_item must be a string", { path: at("rubric_item") });
  }
  if (knownItems && !knownItems.has(f.rubric_item)) {
    throw new SchemaError(
      `unknown rubric item '${f.rubric_item}'`,
      { path: at("rubric_item") }
    );
  }
  if (typeof f.evidence !== "string") {
    throw new SchemaError("evidence must be a string", { path: at("evidence") });
  }
  if (typeof f.screenshot !== "string") {
    throw new SchemaError("screenshot must be a string", { path: at("screenshot") });
  }
  return true;
}

export function validateFindingsDoc(doc, knownItems) {
  if (doc === null || typeof doc !== "object") {
    throw new SchemaError("findings doc is not an object");
  }
  if (doc.schema_version !== SCHEMA_VERSION) {
    throw new SchemaError(
      `schema_version mismatch: expected ${SCHEMA_VERSION}, got ${doc.schema_version}`,
      { path: "schema_version" }
    );
  }
  if (!Array.isArray(doc.findings)) {
    throw new SchemaError("findings must be an array", { path: "findings" });
  }
  doc.findings.forEach((f, i) => validateFinding(f, knownItems, i));
  return true;
}

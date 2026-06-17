// Anthropic vision provider — wraps Messages API with a per-page rubric prompt.
//
// Secrets contract:
//   - ANTHROPIC_API_KEY is read from env at construction time.
//   - The key is NEVER written to findings.json, never logged, never serialized.
//   - The key is only injected into the x-api-key request header.
//
// Resilience contract:
//   - Malformed JSON from the model → analyze() returns a verdict:"error"
//     finding rather than throwing. The caller (analyzer) treats that as
//     "every rubric item for this page is error" if the whole response is
//     unparseable (see analyzer.mjs).
//
// Network: uses global fetch (Node 20+); no SDK dep so we don't have to
// negotiate a vitest-friendly install.

const ANTHROPIC_VERSION = "2023-06-01";
const DEFAULT_MODEL = "claude-3-5-sonnet-latest";
const DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";

import { readFile } from "node:fs/promises";

export class AnthropicProviderError extends Error {
  constructor(message, { cause, kind } = {}) {
    super(message);
    this.name = "AnthropicProviderError";
    this.kind = kind || "unknown";
    if (cause) this.cause = cause;
  }
}

export function createAnthropicProvider({
  apiKey,
  model = DEFAULT_MODEL,
  endpoint = DEFAULT_ENDPOINT,
  fetchImpl = globalThis.fetch,
} = {}) {
  if (!apiKey) {
    throw new AnthropicProviderError(
      "ANTHROPIC_API_KEY missing — provider refuses to construct",
      { kind: "missing_key" }
    );
  }
  return {
    name: "anthropic",
    async analyze({ page, viewport, rubricItem, rubricPrompt, screenshotPath }) {
      let imageB64;
      try {
        const buf = await readFile(screenshotPath);
        imageB64 = buf.toString("base64");
      } catch (e) {
        return errorFinding(page, viewport, rubricItem, screenshotPath,
          `cannot read screenshot: ${e.code || e.message}`);
      }
      const body = {
        model,
        max_tokens: 512,
        messages: [
          {
            role: "user",
            content: [
              {
                type: "image",
                source: {
                  type: "base64",
                  media_type: "image/png",
                  data: imageB64,
                },
              },
              {
                type: "text",
                text:
                  `Rubric: ${rubricItem}\n` +
                  `Page: ${page} @ ${viewport}\n\n` +
                  `${rubricPrompt}\n\n` +
                  `Respond with JSON ONLY: {"verdict":"pass"|"warn"|"fail","evidence":"<quoted phrase>"}`,
              },
            ],
          },
        ],
      };
      let res;
      try {
        res = await fetchImpl(endpoint, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": ANTHROPIC_VERSION,
          },
          body: JSON.stringify(body),
        });
      } catch (e) {
        return errorFinding(page, viewport, rubricItem, screenshotPath,
          `transport error: ${e.message}`);
      }
      if (!res.ok) {
        return errorFinding(page, viewport, rubricItem, screenshotPath,
          `HTTP ${res.status}`);
      }
      let payload;
      try {
        payload = await res.json();
      } catch (e) {
        return errorFinding(page, viewport, rubricItem, screenshotPath,
          `response not JSON: ${e.message}`);
      }
      const text = extractText(payload);
      if (!text) {
        return errorFinding(page, viewport, rubricItem, screenshotPath,
          "empty model response");
      }
      let parsed;
      try {
        parsed = JSON.parse(extractJsonBlock(text));
      } catch {
        return errorFinding(page, viewport, rubricItem, screenshotPath,
          "model returned malformed JSON");
      }
      const verdict = ["pass", "warn", "fail"].includes(parsed.verdict)
        ? parsed.verdict
        : "error";
      const evidence = typeof parsed.evidence === "string" && parsed.evidence
        ? parsed.evidence
        : "(no evidence)";
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

function extractText(payload) {
  if (!payload || !Array.isArray(payload.content)) return null;
  const t = payload.content.find((c) => c.type === "text");
  return t ? t.text : null;
}

function extractJsonBlock(text) {
  const m = text.match(/\{[\s\S]*\}/);
  return m ? m[0] : text;
}

function errorFinding(page, viewport, rubricItem, screenshot, reason) {
  return {
    page,
    viewport,
    rubric_item: rubricItem,
    verdict: "error",
    evidence: `analyzer error: ${reason}`,
    screenshot,
  };
}

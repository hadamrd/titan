import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { createMockProvider } from "../providers/mock.mjs";
import { createAnthropicProvider, AnthropicProviderError } from "../providers/anthropic.mjs";
import { selectProvider } from "../providers/index.mjs";

describe("mock provider", () => {
  it("is deterministic — 100 runs return identical findings", async () => {
    const p = createMockProvider();
    const args = {
      page: "/jobs",
      viewport: "desktop",
      rubricItem: "empty_state_present",
      screenshotPath: "fix/jobs--desktop.png",
    };
    const first = JSON.stringify(await p.analyze(args));
    for (let i = 0; i < 100; i++) {
      const next = JSON.stringify(await p.analyze(args));
      assert.equal(next, first, `iter ${i} drifted`);
    }
  });

  it("produces different verdicts for different rubric items (not all 'pass')", async () => {
    const p = createMockProvider();
    const verdicts = new Set();
    for (const item of ["a", "b", "c", "d", "e", "f", "g"]) {
      const r = await p.analyze({
        page: "/p",
        viewport: "desktop",
        rubricItem: item,
        screenshotPath: "x.png",
      });
      verdicts.add(r.verdict);
    }
    assert.ok(verdicts.size >= 2, `expected variety, got ${[...verdicts]}`);
  });
});

describe("provider selector", () => {
  const silent = { warn() {}, error() {}, log() {} };

  it("defaults to mock when env is empty", () => {
    const p = selectProvider({ env: {}, logger: silent });
    assert.equal(p.name, "mock");
  });

  it("falls back to mock when anthropic provider has no key (NEVER errors)", () => {
    const p = selectProvider({
      env: { TOUR_VISION_PROVIDER: "anthropic" },
      logger: silent,
    });
    assert.equal(p.name, "mock");
  });

  it("falls back to mock for an unknown provider kind", () => {
    const p = selectProvider({
      env: { TOUR_VISION_PROVIDER: "gpt5" },
      logger: silent,
    });
    assert.equal(p.name, "mock");
  });

  it("selects anthropic when key is present", () => {
    const p = selectProvider({
      env: { TOUR_VISION_PROVIDER: "anthropic", ANTHROPIC_API_KEY: "sk-test" },
      logger: silent,
    });
    assert.equal(p.name, "anthropic");
  });
});

describe("anthropic provider — adversarial", () => {
  it("refuses to construct without a key", () => {
    assert.throws(
      () => createAnthropicProvider({ apiKey: "" }),
      (e) => e instanceof AnthropicProviderError && e.kind === "missing_key"
    );
  });

  it("returns verdict:error on transport failure (does NOT throw)", async () => {
    const p = createAnthropicProvider({
      apiKey: "sk-test",
      fetchImpl: async () => {
        throw new Error("ECONNREFUSED");
      },
    });
    // Provide a real readable PNG path so we exercise transport, not file read.
    const r = await p.analyze({
      page: "/x",
      viewport: "desktop",
      rubricItem: "i",
      rubricPrompt: "p",
      screenshotPath: "tour/fixtures/screenshots/jobs--desktop.png",
    });
    assert.equal(r.verdict, "error");
    assert.match(r.evidence, /transport error/);
  });

  it("returns verdict:error on missing screenshot (does NOT throw)", async () => {
    const p = createAnthropicProvider({
      apiKey: "sk-test",
      fetchImpl: async () => new Response("{}", { status: 200 }),
    });
    const r = await p.analyze({
      page: "/x",
      viewport: "desktop",
      rubricItem: "i",
      rubricPrompt: "p",
      screenshotPath: "/does/not/exist.png",
    });
    assert.equal(r.verdict, "error");
    assert.match(r.evidence, /cannot read screenshot/);
  });

  it("returns verdict:error on malformed JSON model response (does NOT crash)", async () => {
    const fetchImpl = async () =>
      new Response(
        JSON.stringify({ content: [{ type: "text", text: "I am not JSON, lol" }] }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    const p = createAnthropicProvider({ apiKey: "sk-test", fetchImpl });
    const r = await p.analyze({
      page: "/x",
      viewport: "desktop",
      rubricItem: "i",
      rubricPrompt: "p",
      screenshotPath: "tour/fixtures/screenshots/jobs--desktop.png",
    });
    assert.equal(r.verdict, "error");
    assert.match(r.evidence, /malformed JSON/);
  });

  it("verifies request shape: model id, base64 image, prompt template, x-api-key header", async () => {
    let captured;
    const fetchImpl = async (url, init) => {
      captured = { url, init };
      return new Response(
        JSON.stringify({
          content: [
            { type: "text", text: '{"verdict":"pass","evidence":"looks fine"}' },
          ],
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      );
    };
    const p = createAnthropicProvider({
      apiKey: "sk-secret",
      model: "claude-3-5-sonnet-latest",
      fetchImpl,
    });
    const r = await p.analyze({
      page: "/jobs",
      viewport: "desktop",
      rubricItem: "empty_state_present",
      rubricPrompt: "is empty state present?",
      screenshotPath: "tour/fixtures/screenshots/jobs--desktop.png",
    });
    assert.equal(r.verdict, "pass");
    assert.equal(captured.url, "https://api.anthropic.com/v1/messages");
    assert.equal(captured.init.headers["x-api-key"], "sk-secret");
    assert.equal(captured.init.headers["anthropic-version"], "2023-06-01");
    const body = JSON.parse(captured.init.body);
    assert.equal(body.model, "claude-3-5-sonnet-latest");
    const img = body.messages[0].content[0];
    assert.equal(img.type, "image");
    assert.equal(img.source.type, "base64");
    assert.equal(img.source.media_type, "image/png");
    assert.ok(img.source.data.length > 0, "expected base64 body");
    const prompt = body.messages[0].content[1].text;
    assert.match(prompt, /Rubric: empty_state_present/);
    assert.match(prompt, /Page: \/jobs @ desktop/);
    assert.match(prompt, /JSON ONLY/);
  });

  it("never echoes the api key into the finding payload", async () => {
    const fetchImpl = async () =>
      new Response(
        JSON.stringify({
          content: [
            { type: "text", text: '{"verdict":"pass","evidence":"ok"}' },
          ],
        }),
        { status: 200 }
      );
    const p = createAnthropicProvider({ apiKey: "sk-SECRET-XYZ", fetchImpl });
    const r = await p.analyze({
      page: "/x",
      viewport: "desktop",
      rubricItem: "i",
      rubricPrompt: "p",
      screenshotPath: "tour/fixtures/screenshots/jobs--desktop.png",
    });
    assert.ok(!JSON.stringify(r).includes("sk-SECRET-XYZ"));
  });
});

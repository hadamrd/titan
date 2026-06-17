// Provider factory — discriminated dispatch on TOUR_VISION_PROVIDER.
//
// Default = mock (CI-safe, deterministic).
// 'anthropic' requires ANTHROPIC_API_KEY; absence falls back to mock with a
// warning per the issue contract (NEVER errors).

import { createMockProvider } from "./mock.mjs";
import { createAnthropicProvider } from "./anthropic.mjs";

export const PROVIDER_KINDS = Object.freeze(["mock", "anthropic"]);

export function selectProvider({ env = process.env, logger = console } = {}) {
  const kind = env.TOUR_VISION_PROVIDER || "mock";
  if (kind === "mock") return createMockProvider();
  if (kind === "anthropic") {
    if (!env.ANTHROPIC_API_KEY) {
      logger.warn(
        "[tour] TOUR_VISION_PROVIDER=anthropic but ANTHROPIC_API_KEY is unset — falling back to mock"
      );
      return createMockProvider();
    }
    return createAnthropicProvider({ apiKey: env.ANTHROPIC_API_KEY });
  }
  logger.warn(`[tour] unknown TOUR_VISION_PROVIDER='${kind}' — falling back to mock`);
  return createMockProvider();
}

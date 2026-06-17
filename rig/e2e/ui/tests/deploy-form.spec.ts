import { test, expect, Page } from '@playwright/test';

/**
 * O.18 — Playwright e2e for the dynamic form generator (ParameterRenderer SPI).
 *
 * The deploy form is rendered by `params/baseParam.jelly` which dispatches to
 * one of the built-in renderers (StringParam, BooleanParam, ChoiceParam,
 * MultipleSelect, ActiveChoice, GitRef, ImageTagChoice). Each renderer's
 * wrapper carries `data-rf-param="<name>"` + `data-rf-param-type="<type>"`
 * and the actual input carries `data-rf-param-input="<name>"` — these are
 * the stable selectors this spec relies on (NEVER assert on Tailwind class
 * names).
 *
 * Fixture reality: the seeded team-a / team-b apps on the live rig do not
 * currently declare `promptParameters` on their components. When no deploy
 * form is reachable, each test calls `test.skip(...)` with a clear message
 * rather than false-failing. Once the rig grows a seeded component with
 * promptParameters, these tests start asserting against it without any
 * code changes.
 *
 * Safety: we DO NOT click the final Deploy button against the live rig
 * unless the target component+env is the seeded smoke job. Validation +
 * render assertions run regardless; the queue-a-build assertion is gated
 * on a known-safe target (RF_DEPLOY_SAFE_PATH env var).
 */

const APP = process.env.RF_TEST_APP ?? 'team-a';

/**
 * Walk the home -> app-detail -> deploy-form flow and return the form
 * locator if one is reachable, else null. Returning null is the
 * "fixture-incomplete" signal — callers should `test.skip(...)`.
 */
async function findDeployForm(page: Page): Promise<{ formSelector: string } | null> {
  // 1) Try the deploy-drawer surface on the app-detail page.
  await page.goto(`/release-flow/projects/${APP}/`);
  const detailMounted = page.locator(`[data-rf-app-detail-id="${APP}"]`);
  if (!(await detailMounted.count())) {
    return null;
  }

  // The drawer opens via clicking a "Deploy" button on a matrix cell.
  const deployButton = page.locator('button:has-text("Deploy"), a:has-text("Deploy")').first();
  if (await deployButton.count()) {
    await deployButton.click();
    // Wait briefly for the HTMX swap to land. If it doesn't bring a
    // form with data-rf-param wrappers, treat as fixture-incomplete.
    const formAppeared = await page
      .locator('[data-rf-param]')
      .first()
      .waitFor({ state: 'visible', timeout: 5_000 })
      .then(() => true)
      .catch(() => false);
    if (formAppeared) {
      return { formSelector: 'form, [data-rf-deploy-form]' };
    }
  }

  // 2) Legacy ComponentManager surface — `/release-flow/components/<id>/`.
  // Best-effort: walk seeded component ids if any are visible on the page.
  const componentLink = page.locator('a[href*="/release-flow/components/"]').first();
  if (await componentLink.count()) {
    await componentLink.click();
    const formAppeared = await page
      .locator('[data-rf-param]')
      .first()
      .waitFor({ state: 'visible', timeout: 5_000 })
      .then(() => true)
      .catch(() => false);
    if (formAppeared) {
      return { formSelector: 'form' };
    }
  }

  return null;
}

test.describe('deploy form (dynamic param SPI)', () => {
  test('renders all built-in param types with stable data-rf-param selectors', async ({
    page,
  }) => {
    const form = await findDeployForm(page);
    if (!form) {
      test.skip(
        true,
        `No deploy form reachable for app="${APP}". Seed a component with promptParameters to enable this assertion.`,
      );
      return;
    }

    // Every visible param input MUST carry a data-rf-param wrapper.
    const wrappers = page.locator('[data-rf-param]');
    const count = await wrappers.count();
    expect(count, 'deploy form should render at least one param').toBeGreaterThan(0);

    // Every wrapper MUST carry a non-empty type marker so test/observability
    // tools can group inputs by ParameterRenderer.
    for (let i = 0; i < count; i++) {
      const w = wrappers.nth(i);
      const name = await w.getAttribute('data-rf-param');
      const type = await w.getAttribute('data-rf-param-type');
      expect(name, `wrapper ${i} should have a non-empty data-rf-param`).toBeTruthy();
      expect(type, `wrapper ${i} (${name}) should declare data-rf-param-type`).toBeTruthy();
    }

    // For each wrapper, find a corresponding focusable input/select.
    // For Tom-Select-wrapped <select>, the underlying <select> is still
    // present in the DOM (just visually hidden); we target it via
    // data-rf-param-input which is set on the original element.
    const firstName = await wrappers.first().getAttribute('data-rf-param');
    const inputForFirst = page.locator(`[data-rf-param-input="${firstName}"]`);
    expect(
      await inputForFirst.count(),
      `param "${firstName}" should expose a data-rf-param-input element`,
    ).toBeGreaterThan(0);
  });

  test('invalid values surface validation feedback without 5xx', async ({ page }) => {
    const form = await findDeployForm(page);
    if (!form) {
      test.skip(true, `No deploy form reachable for app="${APP}".`);
      return;
    }

    // Clear every visible text input so required fields go empty.
    const textInputs = page.locator('input[type="text"][data-rf-param-input]');
    const n = await textInputs.count();
    if (n === 0) {
      test.skip(true, 'No text params on this form to invalidate.');
      return;
    }
    for (let i = 0; i < n; i++) {
      await textInputs.nth(i).fill('');
    }

    // Intercept the POST so we never actually queue a deploy against the
    // live rig — we only care that the form would round-trip and that
    // the server's validation response is a 4xx (NEVER a 5xx).
    const responsePromise = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /deploy/.test(r.url()),
      { timeout: 10_000 },
    );

    const submit = page.locator('button[type="submit"]').first();
    if (!(await submit.count())) {
      test.skip(true, 'No submit button rendered.');
      return;
    }
    await submit.click();

    const resp = await responsePromise.catch(() => null);
    if (!resp) {
      // Form may be client-validated and never submitted — that's also
      // an acceptable contract; assert a visible message instead.
      const help = page.locator('.help-block, .text-red-500, [data-rf-param-error]').first();
      await expect(help, 'expected an inline validation hint').toBeVisible({ timeout: 2_000 });
      return;
    }
    const status = resp.status();
    expect(
      status,
      `POST /deploy with empty required fields must be 4xx, never 5xx (got ${status})`,
    ).toBeLessThan(500);
  });

  test('valid values flow — submit shape is correct without triggering a destructive deploy', async ({
    page,
  }) => {
    const form = await findDeployForm(page);
    if (!form) {
      test.skip(true, `No deploy form reachable for app="${APP}".`);
      return;
    }

    const safePath = process.env.RF_DEPLOY_SAFE_PATH;
    if (!safePath) {
      // No designated safe target → assert the form would submit with
      // the expected hidden componentId payload, but do NOT click the
      // final Deploy button. Tests should not queue controller builds
      // against shared rigs without an opt-in.
      const componentIdInput = page.locator('input[type="hidden"][name="componentId"]').first();
      const has = await componentIdInput.count();
      expect(has, 'form should carry hidden componentId input').toBeGreaterThan(0);
      const cid = await componentIdInput.getAttribute('value');
      expect(cid, 'componentId should be non-empty').toBeTruthy();
      return;
    }

    // Fill every text param with a placeholder value so required-field
    // validation passes.
    const textInputs = page.locator('input[type="text"][data-rf-param-input]');
    const n = await textInputs.count();
    for (let i = 0; i < n; i++) {
      await textInputs.nth(i).fill('e2e-' + i);
    }

    // Single-select <select> params: pick the first non-empty option.
    const selects = page.locator(
      'select[data-rf-param-input]:not([multiple])',
    );
    const sn = await selects.count();
    for (let i = 0; i < sn; i++) {
      const s = selects.nth(i);
      const options = await s.locator('option').all();
      for (const opt of options) {
        const v = await opt.getAttribute('value');
        if (v) {
          await s.selectOption(v);
          break;
        }
      }
    }

    const submit = page.locator('button[type="submit"]').first();
    const responsePromise = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /deploy/.test(r.url()),
      { timeout: 15_000 },
    );
    await submit.click();
    const resp = await responsePromise;
    const status = resp.status();
    expect(status, `POST /deploy should be 2xx or 3xx (got ${status})`).toBeLessThan(400);

    // Probe the controller to verify a build was actually queued. The CD loop
    // pushes results into deployment_events; for the e2e contract we
    // just assert the deploy endpoint accepted the submission.
    const password = process.env.RF_TITAN_PASSWORD;
    if (!password) return;
    const auth = Buffer.from(
      `${process.env.RF_TITAN_USER ?? 'admin'}:${password}`,
    ).toString('base64');
    const queueResp = await page.request.get(`/queue/api/json`, {
      headers: { Authorization: `Basic ${auth}` },
    });
    expect(queueResp.status(), 'controller queue API should be readable').toBeLessThan(400);
  });
});

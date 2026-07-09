/**
 * v3 log-search (#1097) — in-log Ctrl+F search over the streamed build log.
 *
 * SRE story: I'm staring at a 9-chunk seeded log for titan-hello#1, I want to
 * find the lines containing "Hello" without alt-tabbing to a terminal. I hit
 * Ctrl+F, a search bar appears in the top-right of the terminal pane, I type
 * "Hello", I see highlights + a counter, I cycle through with Enter / Shift-
 * Enter, I close with Esc.
 *
 * Anchors on titan-hello#1 — the same seeded build the 10-log-stream spec
 * uses (9 chunks via task token 4e110000-…-3110). The seed inserts the
 * literal string "Hello from Titan" so we have a known substring to search.
 *
 * Assertions are LIGHTWEIGHT (memory: Playwright Lightweight Checks):
 *   - opens via Ctrl+F (window-scoped keydown intercept)
 *   - <mark> elements appear after typing
 *   - counter matches the DOM mark count
 *   - Esc closes the bar
 *   - adversarial: typing `.*?` (regex special chars) treated literally
 *     — no error toast, counter shows 0 of 0 (or matches if a literal match
 *     existed in the seed; titan-hello#1 has none).
 *
 * @golden promotion audit (#123): READ-ONLY against the seeded titan-hello#1
 * build (pgClient does a single SELECT; the ownership rule explicitly allows
 * reads of foreign rows). Creates no rows, mutates nothing, needs no
 * teardown; the seed dependency is the same contract 00-seed-guard and
 * 10-log-stream already enforce. Deterministic — Playwright waiters only.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

let helloBuildId: number

test.beforeAll(async () => {
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string }>(
      `SELECT b.id::text AS id
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE j.full_name='titan-hello' AND b.build_number=1
        LIMIT 1`,
    )
    const row = res.rows[0]
    if (!row) {
      throw new Error("titan-hello#1 missing — re-run rig/local/seed-data.sh")
    }
    helloBuildId = Number(row.id)
  } finally {
    await client.end()
  }
})

// NOTE: @golden must precede the "(#1097)" parenthetical — the golden-count
// helper's regex stops at the first `)` in the title line.
test.describe('v3 in-log search @golden (#1097)', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/builds/${helloBuildId}`)
    await page.getByRole('tab', { name: /^logs$/i }).click()
    // Wait for ≥1 log line so the search has something to highlight.
    await expect(page.locator('.log-line').first()).toBeVisible({ timeout: 10_000 })
  })

  test('Ctrl+F opens search bar, typing "Hello" highlights matches, counter visible, Esc closes', async ({
    page,
  }) => {
    // Bar must not be present before Ctrl+F.
    await expect(page.getByTestId('log-search')).toBeHidden()

    // Focus the terminal area first so the window-level Ctrl+F intercept
    // fires (it's gated by isTypingTarget — the body is fine).
    await page.locator('body').click()

    // Trigger Ctrl+F.
    await page.keyboard.press('Control+f')

    // The search bar appears with input focused.
    const bar = page.getByTestId('log-search')
    await expect(bar).toBeVisible({ timeout: 2_000 })
    const input = page.getByTestId('log-search-input')
    await expect(input).toBeFocused()

    // Type "Hello" — the seed inserts "Hello from Titan" into titan-hello#1
    // logs (verified independently in 10-log-stream.spec.ts).
    await input.fill('Hello')

    // <mark> elements appear inside the terminal body.
    const marks = page.locator('[data-testid="terminal-body"] mark')
    await expect(marks.first()).toBeVisible({ timeout: 2_000 })
    const markCount = await marks.count()
    expect(markCount, 'expected ≥1 highlight for "Hello" in titan-hello#1 seed').toBeGreaterThan(0)

    // Counter format is "N of M" per the LogSearchBar contract; assert it
    // reflects the same total the DOM shows.
    const counterText = (await page.getByTestId('log-search-counter').textContent())?.trim()
    expect(counterText).toMatch(/^\d+ of \d+$/)
    const m = /^(\d+) of (\d+)$/.exec(counterText!)
    expect(m).not.toBeNull()
    expect(Number(m![2])).toBe(markCount)

    // Esc closes the bar.
    await input.press('Escape')
    await expect(bar).toBeHidden({ timeout: 1_000 })
  })

  test('Enter / Shift+Enter cycle through matches', async ({ page }) => {
    await page.locator('body').click()
    await page.keyboard.press('Control+f')
    const input = page.getByTestId('log-search-input')
    await expect(input).toBeFocused()
    await input.fill('Hello')

    const counter = page.getByTestId('log-search-counter')
    const startText = (await counter.textContent())?.trim()
    expect(startText).toMatch(/^\d+ of \d+$/)
    const total = Number(/of (\d+)$/.exec(startText!)![1])

    // Only meaningful if there are ≥2 matches; titan-hello#1 seed has
    // "Hello from Titan" repeated, so total is typically ≥2. If not, we
    // assert wrap-on-single still produces a sane counter rather than
    // failing on seed shape.
    if (total >= 2) {
      await input.press('Enter')
      await expect(counter).toHaveText(/^2 of \d+$/, { timeout: 1_000 })
      await input.press('Shift+Enter')
      await expect(counter).toHaveText(/^1 of \d+$/, { timeout: 1_000 })
      // Shift+Enter from idx 0 wraps to last.
      await input.press('Shift+Enter')
      await expect(counter).toHaveText(new RegExp(`^${total} of ${total}$`), { timeout: 1_000 })
    } else {
      // Single match: cycling stays at "1 of 1".
      await input.press('Enter')
      await expect(counter).toHaveText(/^1 of 1$/, { timeout: 1_000 })
    }
  })

  test('adversarial: query containing regex specials (.*?) is treated literally — no crash, no error', async ({
    page,
  }) => {
    // The hook validates regex source ONLY in regex mode. The default mode
    // is literal, so `.*?` must be escaped and matched literally. If we
    // accidentally compiled it as a regex it would match every line (and
    // the counter would blow up to ~9), which would fail this assertion.
    await page.locator('body').click()
    await page.keyboard.press('Control+f')
    const input = page.getByTestId('log-search-input')
    await expect(input).toBeFocused()
    await input.fill('.*?')

    // titan-hello#1 seed does NOT contain the literal substring `.*?` —
    // so the counter must read "0 of 0", NOT some large number (which
    // would prove we treated it as a regex wildcard).
    await expect(page.getByTestId('log-search-counter')).toHaveText('0 of 0', {
      timeout: 1_000,
    })

    // Adversarial: no <mark> should have been spawned.
    expect(await page.locator('[data-testid="terminal-body"] mark').count()).toBe(0)

    // And no error toast / aria-invalid on the input (literal mode never
    // surfaces regexError).
    expect(await input.getAttribute('aria-invalid')).toBe('false')
  })
})

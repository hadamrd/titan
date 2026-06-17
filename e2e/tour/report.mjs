#!/usr/bin/env node
/**
 * report.mjs — operator-tour HTML report generator (#1132).
 *
 * Walks e2e/tour/baseline/ and e2e/tour/current/, pairs PNGs by filename,
 * computes a coarse pixel-diff verdict (byte-equal → "match"; differing
 * sizes → "size-drift"; differing contents → "diff"), and emits
 * e2e/tour/report.html with a side-by-side gallery.
 *
 * Zero dependencies on purpose — keeps the harness skeleton runnable on
 * a fresh checkout without `pnpm install` first. A future ticket can swap
 * in pixelmatch / odiff for proper diff overlays.
 */
import { readdir, readFile, stat, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const BASELINE = join(HERE, 'baseline')
const CURRENT = join(HERE, 'current')
const REPORT = join(HERE, 'report.html')

async function listPngs(dir) {
  if (!existsSync(dir)) return []
  const names = await readdir(dir)
  return names.filter((n) => n.endsWith('.png')).sort()
}

async function hashFile(path) {
  const buf = await readFile(path)
  return { hash: createHash('sha256').update(buf).digest('hex'), size: buf.length }
}

function pairs(baseline, current) {
  const all = new Set([...baseline, ...current])
  return [...all].sort().map((name) => ({
    name,
    inBaseline: baseline.includes(name),
    inCurrent: current.includes(name),
  }))
}

function badge(verdict) {
  const colors = {
    match: '#22c55e',
    diff: '#ef4444',
    'size-drift': '#f59e0b',
    'baseline-missing': '#6366f1',
    'current-missing': '#9ca3af',
  }
  return `<span class="badge" style="background:${colors[verdict] ?? '#999'}">${verdict}</span>`
}

async function main() {
  const baselinePngs = await listPngs(BASELINE)
  const currentPngs = await listPngs(CURRENT)
  const rows = pairs(baselinePngs, currentPngs)

  const cells = []
  let diffCount = 0
  for (const row of rows) {
    let verdict
    let detail = ''
    if (!row.inBaseline) {
      verdict = 'baseline-missing'
      detail = 'no baseline — run <code>task tour:accept</code> to promote.'
    } else if (!row.inCurrent) {
      verdict = 'current-missing'
      detail = 'current run produced no PNG with this name.'
    } else {
      const b = await hashFile(join(BASELINE, row.name))
      const c = await hashFile(join(CURRENT, row.name))
      if (b.hash === c.hash) verdict = 'match'
      else if (b.size !== c.size) verdict = 'size-drift'
      else verdict = 'diff'
      detail = `baseline ${b.size}B · current ${c.size}B`
    }
    if (verdict === 'diff' || verdict === 'size-drift') diffCount += 1

    const baseSrc = row.inBaseline ? `baseline/${row.name}` : ''
    const currSrc = row.inCurrent ? `current/${row.name}` : ''
    cells.push(`
      <section class="row" data-verdict="${verdict}">
        <header>
          <h2>${row.name}</h2>
          ${badge(verdict)}
          <small>${detail}</small>
        </header>
        <div class="pair">
          <figure>
            <figcaption>baseline</figcaption>
            ${baseSrc ? `<a href="${baseSrc}"><img loading="lazy" src="${baseSrc}" alt="baseline ${row.name}"></a>` : '<div class="missing">— missing —</div>'}
          </figure>
          <figure>
            <figcaption>current</figcaption>
            ${currSrc ? `<a href="${currSrc}"><img loading="lazy" src="${currSrc}" alt="current ${row.name}"></a>` : '<div class="missing">— missing —</div>'}
          </figure>
        </div>
      </section>`)
  }

  const generatedAt = new Date().toISOString()
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Operator tour — ${rows.length} frames (${diffCount} diffs)</title>
<style>
  :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
  body { margin: 0; padding: 24px; max-width: 1600px; margin-inline: auto; }
  h1 { margin: 0 0 4px; }
  .summary { color: #666; margin-bottom: 24px; }
  .row { border: 1px solid #ddd; border-radius: 8px; margin: 16px 0; padding: 12px; }
  .row[data-verdict="diff"] { border-color: #ef4444; background: #fef2f2; }
  .row[data-verdict="size-drift"] { border-color: #f59e0b; background: #fffbeb; }
  .row[data-verdict="baseline-missing"] { border-color: #6366f1; background: #eef2ff; }
  header { display: flex; gap: 12px; align-items: baseline; flex-wrap: wrap; }
  header h2 { margin: 0; font-size: 1rem; font-family: ui-monospace, monospace; }
  .badge { color: white; padding: 2px 8px; border-radius: 999px; font-size: 0.8rem; }
  .pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
  figure { margin: 0; }
  figcaption { font-size: 0.75rem; color: #666; margin-bottom: 4px; }
  img { width: 100%; height: auto; border: 1px solid #ccc; border-radius: 4px; background: #fff; }
  .missing { padding: 40px; text-align: center; color: #999; background: #f5f5f5; border-radius: 4px; }
</style>
</head>
<body>
  <h1>Operator tour report</h1>
  <p class="summary">
    Generated ${generatedAt} · ${rows.length} frames · <strong>${diffCount}</strong> diffs vs baseline.
    Accept current → baseline with <code>task tour:accept</code>.
  </p>
  ${cells.join('\n')}
</body>
</html>`

  await writeFile(REPORT, html, 'utf-8')
  console.log(
    `tour: wrote ${REPORT} · ${rows.length} frames · ${diffCount} diffs vs baseline`,
  )
  // Exit code: non-zero if there's a diff so a future CI step can gate.
  // Today task tour:run also runs the capture; the operator inspects manually.
  process.exit(diffCount > 0 ? 1 : 0)
}

main().catch((e) => {
  console.error('tour: report generation failed:', e)
  process.exit(2)
})

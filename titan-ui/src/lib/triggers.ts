/**
 * Light parser for the `triggers:` block of a Titan pipeline YAML.
 *
 * We deliberately DO NOT pull a YAML library — the triggers block is fixed
 * enough that a tiny line-oriented extractor suffices, and keeps the bundle
 * lean (CONSTITUTION: pre-commit gate 5 / vite build).
 *
 * Output is a discriminated union — callers `switch (t.kind)` and never
 * sniff strings (feedback_principled_typed_design).
 */

export type ParsedTrigger =
  | { kind: 'cron'; expr: string; humanized: string }
  | { kind: 'github'; branches: string[]; credentialsId: string | null }
  | { kind: 'unknown'; raw: string }

export type TriggerParseResult =
  | { kind: 'ok'; triggers: ParsedTrigger[] } // empty list = on-demand
  | { kind: 'error'; reason: string }

/** Public entry — never throws. */
export function parseTriggers(pipelineScript: string | null | undefined): TriggerParseResult {
  if (!pipelineScript || pipelineScript.trim() === '') {
    return { kind: 'ok', triggers: [] }
  }
  try {
    return { kind: 'ok', triggers: extractTriggers(pipelineScript) }
  } catch (err) {
    return { kind: 'error', reason: err instanceof Error ? err.message : 'parse failed' }
  }
}

// ── Internals ───────────────────────────────────────────────────────────────

/**
 * Extract the top-level `triggers:` block by indentation. Returns the list of
 * entries underneath as ParsedTrigger items. Supports the two canonical
 * shapes that the engine itself recognises:
 *
 *   triggers:
 *     - cron: "0 *\/6 * * *"
 *     - github:
 *         branches: [trunk, main]
 *
 *   triggers:
 *     - github: { branches: ["trunk"] }
 *
 *   triggers: []                           # explicit on-demand
 */
function extractTriggers(yaml: string): ParsedTrigger[] {
  const lines = yaml.split(/\r?\n/)
  const headerIdx = lines.findIndex((l) => /^triggers\s*:/.test(l))
  if (headerIdx === -1) return []

  const header = lines[headerIdx]
  // Inline empty list: `triggers: []`
  const inline = header.split(':').slice(1).join(':').trim()
  if (inline === '[]') return []
  if (inline !== '') {
    // We don't support arbitrary inline flow-sequences for v0.1.0; treat as
    // unparseable rather than silently dropping them.
    throw new Error('inline triggers list not supported')
  }

  // Collect all subsequent lines indented deeper than the header (0 spaces).
  // Top-level `triggers:` is column 0; child list items start with 2 spaces.
  const block: string[] = []
  for (let i = headerIdx + 1; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === '') {
      block.push(line)
      continue
    }
    const indent = line.match(/^ */)![0].length
    if (indent === 0) break
    block.push(line)
  }

  return parseTriggerBlock(block)
}

function parseTriggerBlock(block: string[]): ParsedTrigger[] {
  const out: ParsedTrigger[] = []
  // Split by leading `- ` markers.
  let current: string[] = []
  const flush = () => {
    if (current.length === 0) return
    const t = classifyEntry(current)
    if (t) out.push(t)
    current = []
  }
  for (const line of block) {
    if (/^\s*-\s/.test(line)) {
      flush()
      current.push(line)
    } else if (line.trim() !== '') {
      current.push(line)
    }
  }
  flush()
  return out
}

function classifyEntry(entryLines: string[]): ParsedTrigger | null {
  // Strip the leading "- " from the first line; subsequent lines keep their indent.
  const first = entryLines[0].replace(/^\s*-\s+/, '')
  const joined = [first, ...entryLines.slice(1).map((l) => l.replace(/^\s+/, ''))].join('\n')

  // cron: "<expr>"   OR   cron: <expr>
  const cron = joined.match(/^cron\s*:\s*(.+?)\s*$/m)
  if (cron && cron.index === 0) {
    const expr = unquote(cron[1])
    return { kind: 'cron', expr, humanized: humanizeCron(expr) }
  }

  // github: ...
  if (/^github\s*:/.test(joined)) {
    const branches = extractBranches(joined)
    const credentialsId = extractCredentialsId(joined)
    return { kind: 'github', branches, credentialsId }
  }

  return { kind: 'unknown', raw: joined.split('\n')[0] }
}

function unquote(s: string): string {
  const t = s.trim()
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
    return t.slice(1, -1)
  }
  return t
}

function extractBranches(entry: string): string[] {
  // Flow-style:  github: { branches: ["trunk", "main"] }
  const flow = entry.match(/branches\s*:\s*\[([^\]]*)\]/)
  if (flow) {
    return flow[1]
      .split(',')
      .map((s) => unquote(s.trim()))
      .filter((s) => s.length > 0)
  }
  // Block-style:
  //   github:
  //     branches:
  //       - trunk
  //       - main
  const blockHdr = entry.match(/branches\s*:\s*$/m)
  if (blockHdr) {
    const after = entry.slice(blockHdr.index! + blockHdr[0].length)
    const items: string[] = []
    for (const line of after.split('\n')) {
      const m = line.match(/^\s*-\s+(.+?)\s*$/)
      if (m) items.push(unquote(m[1]))
      else if (line.trim() !== '' && !/^\s+/.test(line)) break
    }
    return items
  }
  return []
}

function extractCredentialsId(entry: string): string | null {
  // Flow style: github: { credentialsId: "abc", ... }
  const flow = entry.match(/credentialsId\s*:\s*([^,}\n]+)/)
  if (flow) {
    const v = unquote(flow[1])
    return v.length > 0 ? v : null
  }
  return null
}

// ── Serializer (ticket #439) ────────────────────────────────────────────────
//
// Turns a ParsedTrigger[] back into the canonical YAML block — the same shape
// the parser above accepts. The serializer is total over the discriminated
// union and is the inverse of `parseTriggers` for the supported kinds (cron,
// github, on-demand). `unknown` is dropped: we never write back something we
// couldn't recognise.
//
// The output is indented to live under a top-level `triggers:` key (two-space
// indent on list items, four-space indent on nested keys) so callers can
// substitute it into an existing YAML document by line-range replacement.

export function serializeTriggers(triggers: ParsedTrigger[]): string {
  if (triggers.length === 0) return 'triggers: []'
  const lines: string[] = ['triggers:']
  for (const t of triggers) {
    switch (t.kind) {
      case 'cron':
        lines.push(`  - cron: "${t.expr}"`)
        break
      case 'github': {
        const branchesYaml = `[${t.branches.map((b) => `"${b}"`).join(', ')}]`
        lines.push('  - github:')
        lines.push(`      branches: ${branchesYaml}`)
        if (t.credentialsId && t.credentialsId.length > 0) {
          lines.push(`      credentialsId: "${t.credentialsId}"`)
        }
        break
      }
      case 'unknown':
        // Skip — the user explicitly removed an unparseable trigger by editing.
        break
    }
  }
  return lines.join('\n')
}

/**
 * Replace the existing `triggers:` block in {@code pipelineScript} with a
 * fresh serialization. If the script has no `triggers:` block, the new block
 * is appended at the top (right after a leading `titan:` or at the file head).
 * Whitespace and surrounding keys are preserved.
 */
export function replaceTriggersBlock(
  pipelineScript: string,
  triggers: ParsedTrigger[],
): string {
  const newBlock = serializeTriggers(triggers)
  const lines = pipelineScript.split(/\r?\n/)
  const headerIdx = lines.findIndex((l) => /^triggers\s*:/.test(l))
  if (headerIdx === -1) {
    // No existing block — prepend. Keep a trailing newline so the document
    // body that follows isn't glued onto the last line of the block.
    const trimmed = pipelineScript.replace(/^\s*\n/, '')
    return `${newBlock}\n${trimmed}`
  }
  // Find the end of the existing block (first line back to indent 0 or EOF).
  let endIdx = lines.length
  for (let i = headerIdx + 1; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === '') continue
    const indent = line.match(/^ */)![0].length
    if (indent === 0) {
      endIdx = i
      break
    }
  }
  const before = lines.slice(0, headerIdx)
  const after = lines.slice(endIdx)
  return [...before, ...newBlock.split('\n'), ...after].join('\n')
}

/** A blank trigger of the requested kind — used when the user adds a row. */
export function blankTrigger(kind: ParsedTrigger['kind']): ParsedTrigger {
  switch (kind) {
    case 'cron': {
      const expr = '0 */1 * * *'
      return { kind: 'cron', expr, humanized: humanizeCron(expr) }
    }
    case 'github':
      return { kind: 'github', branches: ['trunk'], credentialsId: null }
    case 'unknown':
      return { kind: 'unknown', raw: '' }
  }
}

// ── Cron humanizer ──────────────────────────────────────────────────────────
// Deliberately tiny — covers the common patterns users actually paste. Falls
// back to the literal expression on anything fancier.
export function humanizeCron(expr: string): string {
  const parts = expr.trim().split(/\s+/)
  if (parts.length !== 5) return expr
  const [min, hour, dom, mon, dow] = parts

  if (min === '*' && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    return 'every minute'
  }
  const everyN = hour.match(/^\*\/(\d+)$/)
  if (min === '0' && everyN && dom === '*' && mon === '*' && dow === '*') {
    return `every ${everyN[1]}h`
  }
  const everyNMin = min.match(/^\*\/(\d+)$/)
  if (everyNMin && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    return `every ${everyNMin[1]}m`
  }
  if (/^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && dow === '*') {
    const h = hour.padStart(2, '0')
    const m = min.padStart(2, '0')
    return `daily at ${h}:${m}`
  }
  return expr
}

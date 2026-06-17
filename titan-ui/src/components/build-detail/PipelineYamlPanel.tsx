/**
 * Pipeline YAML panel (issue #533). Extracted from `/builds/$buildId.tsx`
 * (ticket #851). Unchanged behaviour — exported through the route shim
 * because build-yaml-tab.test imports it from `routes/builds/$buildId`.
 */
import React, { useMemo, useState } from 'react'
import hljs from 'highlight.js/lib/core'
import yamlLang from 'highlight.js/lib/languages/yaml'
import { Button } from '@/components/ui/Button'

// Register YAML only — avoids pulling in the full ~80 language bundle.
hljs.registerLanguage('yaml', yamlLang)

export function PipelineYamlPanel({
  pipelineScript,
}: {
  pipelineScript: string | null
}) {
  const [copied, setCopied] = useState(false)

  // Memoize highlight pass — input is stable across renders unless the
  // backend ships fresh YAML. We convert hljs' HTML output into a React
  // node tree via DOMParser so we never have to use dangerouslySetInnerHTML
  // (CONSTITUTION §6 — no inline HTML injection, CSP-clean).
  const highlightedNodes = useMemo(() => {
    if (pipelineScript === null) return null
    try {
      const html = hljs.highlight(pipelineScript, { language: 'yaml' }).value
      const doc = new DOMParser().parseFromString(
        `<root>${html}</root>`,
        'text/html',
      )
      const root = doc.body.firstChild
      if (!root) return null
      const convert = (node: Node, key: number): React.ReactNode => {
        if (node.nodeType === Node.TEXT_NODE) return node.nodeValue
        if (node.nodeType === Node.ELEMENT_NODE) {
          const el = node as Element
          const className = el.getAttribute('class') ?? undefined
          const children = Array.from(el.childNodes).map((c, i) => convert(c, i))
          return React.createElement(
            'span',
            { key, className },
            ...children,
          )
        }
        return null
      }
      return Array.from(root.childNodes).map((c, i) => convert(c, i))
    } catch {
      // Fall back to plain text on tokenizer crash — never leave the SRE
      // staring at a blank pane.
      return null
    }
  }, [pipelineScript])

  const onCopy = () => {
    if (pipelineScript === null) return
    void navigator.clipboard.writeText(pipelineScript).then(
      () => {
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      },
      () => {
        // Clipboard refused. Keep label as-is.
      },
    )
  }

  if (pipelineScript === null) {
    return (
      <div className="card" data-testid="yaml-empty">
        <div className="card-header">
          <h3 className="card-title">Pipeline YAML</h3>
        </div>
        <div className="card-body">
          <p style={{ color: 'var(--fg-dim)', fontSize: 13, margin: 0 }}>
            YAML not captured for this build (legacy).{' '}
            <a
              href="https://github.com/hadamrd/titan/issues/533"
              target="_blank"
              rel="noreferrer"
              style={{ color: 'var(--fg-muted)', textDecoration: 'underline' }}
            >
              Tracking issue #533
            </a>
            .
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="card" data-testid="yaml-panel">
      <div
        className="card-header"
        style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
      >
        <h3 className="card-title">Pipeline YAML</h3>
        <Button
          variant="outline"
          size="sm"
          onClick={onCopy}
          data-testid="yaml-copy-button"
          aria-label="Copy pipeline YAML to clipboard"
        >
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
      <pre
        data-testid="yaml-body"
        style={{
          margin: 0,
          padding: 14,
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          lineHeight: 1.55,
          overflow: 'auto',
          maxHeight: 'min(60vh, 520px)',
          whiteSpace: 'pre',
          color: 'var(--fg)',
          background: 'var(--bg-elevated, transparent)',
          borderTop: '1px solid var(--border)',
        }}
      >
        {highlightedNodes !== null ? (
          <code className="hljs language-yaml">{highlightedNodes}</code>
        ) : (
          <code>{pipelineScript}</code>
        )}
      </pre>
    </div>
  )
}

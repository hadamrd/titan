/**
 * OutputsPanel — per-step key/value outputs (closes #782).
 *
 * Surfaces the {@code outputs} map a step published via {@code setOutput(k, v)}
 * (design/29 §6 — the templating source for {@code ${{ steps[...].outputs.X }}}).
 * Mounted in the Steps tab of /builds/$id alongside NodeDetail.
 *
 * Rendering rules (issue #782 acceptance):
 *  - 0 outputs → muted "(no outputs)" placeholder, no table chrome.
 *  - N outputs → N rows, each row a (key, value, copy) triple.
 *  - Multi-line value → <pre> with wrap + max-height + vertical scroll, never
 *    inline-truncated (an SRE debugging a step that published a 50-line PEM
 *    needs to see the whole thing).
 *  - Copy button → navigator.clipboard.writeText(value); label flips to
 *    "Copied" for 1.5s, then back. Refusal is a silent no-op (private mode).
 *
 * Security note: outputs are user-published step content. Credentials resolved
 * by the controller never round-trip back into result_json, so the panel does
 * not need a second masker. A step that explicitly publishes a secret-derived
 * value is publishing it intentionally — downstream stages will template against
 * the same string. See FlowNodeDto javadoc for the policy reference.
 */
import { useState } from 'react'

export interface OutputsPanelProps {
  /** The flow node's published outputs. Undefined / empty → empty state. */
  outputs: Record<string, string> | undefined
}

const MULTILINE_RE = /\r?\n/

export function OutputsPanel({ outputs }: OutputsPanelProps) {
  const entries = outputs ? Object.entries(outputs) : []

  if (entries.length === 0) {
    return (
      <div className="card" data-testid="outputs-panel">
        <div className="card-header">
          <h3 className="card-title">Outputs</h3>
        </div>
        <div className="card-body">
          <p
            data-testid="outputs-empty"
            style={{ color: 'var(--fg-dim)', fontSize: 12, margin: 0 }}
          >
            (no outputs)
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="card" data-testid="outputs-panel">
      <div className="card-header">
        <h3 className="card-title">
          Outputs <span style={{ color: 'var(--fg-dim)', fontWeight: 400 }}>({entries.length})</span>
        </h3>
      </div>
      <div
        className="card-body"
        style={{ padding: 0, borderTop: '1px solid var(--border)' }}
      >
        <table
          data-testid="outputs-table"
          style={{
            width: '100%',
            borderCollapse: 'collapse',
            fontSize: 12,
            tableLayout: 'fixed',
          }}
        >
          <colgroup>
            <col style={{ width: '30%' }} />
            <col />
            <col style={{ width: 70 }} />
          </colgroup>
          <tbody>
            {entries.map(([key, value]) => (
              <OutputRow key={key} outputKey={key} value={value} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function OutputRow({ outputKey, value }: { outputKey: string; value: string }) {
  const [copied, setCopied] = useState(false)
  const multiline = MULTILINE_RE.test(value)

  const onCopy = () => {
    void navigator.clipboard.writeText(value).then(
      () => {
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      },
      () => {
        // Clipboard refused (private mode / no permission). Silent no-op.
      },
    )
  }

  return (
    <tr
      data-testid={`outputs-row-${outputKey}`}
      style={{ borderBottom: '1px solid var(--border)', verticalAlign: 'top' }}
    >
      <td
        style={{
          padding: '8px 12px',
          fontFamily: 'var(--font-mono)',
          color: 'var(--fg-muted)',
          wordBreak: 'break-all',
        }}
      >
        {outputKey}
      </td>
      <td style={{ padding: '8px 12px' }}>
        {multiline ? (
          <pre
            data-testid={`outputs-value-${outputKey}`}
            style={{
              margin: 0,
              fontFamily: 'var(--font-mono)',
              fontSize: 12,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              maxHeight: 200,
              overflow: 'auto',
              color: 'var(--fg)',
            }}
          >
            {value}
          </pre>
        ) : (
          <span
            data-testid={`outputs-value-${outputKey}`}
            style={{
              fontFamily: 'var(--font-mono)',
              wordBreak: 'break-all',
              color: 'var(--fg)',
            }}
          >
            {value}
          </span>
        )}
      </td>
      <td style={{ padding: '8px 12px', textAlign: 'right' }}>
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          data-testid={`outputs-copy-${outputKey}`}
          aria-label={`Copy value of ${outputKey}`}
          onClick={onCopy}
          style={{
            fontSize: 11,
            padding: '2px 8px',
            border: '1px solid var(--border)',
            borderRadius: 4,
            background: 'transparent',
            color: 'var(--fg-muted)',
            cursor: 'pointer',
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </td>
    </tr>
  )
}

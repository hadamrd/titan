/**
 * Unit coverage for the build-failure root-cause badge (#1105).
 *
 * The server-side classifier writes `failureCause` (+ `failureCauseDetail`) on a FAILED build;
 * {@link FailureCauseBadge} surfaces it as a colored badge with a "why this was classified"
 * tooltip carrying the matching log snippet.
 *
 * Adversarial-first: covers the happy mapping for every cause kind, the snippet-driven tooltip,
 * the per-cause fallback tooltip when no snippet, an unrecognised cause string (→ unknown), and
 * the absent-cause case (renders nothing).
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { FailureCauseBadge } from '../components/FailureCauseBadge'

describe('FailureCauseBadge (#1105)', () => {
  it('renders a labeled badge for each known cause', () => {
    const cases: Array<[string, string]> = [
      ['test_failure', 'Test failure'],
      ['compile_error', 'Compile error'],
      ['oom', 'Out of memory'],
      ['timeout', 'Timeout'],
      ['network', 'Network'],
      ['rate_limit', 'Rate limited'],
      ['unknown', 'Cause unknown'],
    ]
    for (const [cause, label] of cases) {
      const { unmount } = render(<FailureCauseBadge failureCause={cause} />)
      expect(screen.getByTestId('failure-cause-badge')).toHaveTextContent(label)
      expect(screen.getByTestId('failure-cause-badge')).toHaveAttribute('data-cause', cause)
      unmount()
    }
  })

  it('puts the matching log snippet in the tooltip when present', () => {
    render(
      <FailureCauseBadge
        failureCause="oom"
        failureCauseDetail="java.lang.OutOfMemoryError: Java heap space"
      />,
    )
    expect(screen.getByTestId('failure-cause-badge')).toHaveAttribute(
      'title',
      'Why this was classified: java.lang.OutOfMemoryError: Java heap space',
    )
  })

  it('falls back to a per-cause explanation when there is no snippet', () => {
    render(<FailureCauseBadge failureCause="network" failureCauseDetail={null} />)
    expect(screen.getByTestId('failure-cause-badge')).toHaveAttribute(
      'title',
      'A network / DNS error interrupted the build.',
    )
  })

  it('treats an unrecognised cause string as unknown rather than crashing', () => {
    render(<FailureCauseBadge failureCause="solar_flare" />)
    expect(screen.getByTestId('failure-cause-badge')).toHaveTextContent('Cause unknown')
  })

  it('renders nothing when there is no cause (success / not-yet-classified)', () => {
    const { container } = render(<FailureCauseBadge failureCause={null} />)
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByTestId('failure-cause-badge')).toBeNull()
  })
})

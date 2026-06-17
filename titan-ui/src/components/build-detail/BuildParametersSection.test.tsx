/**
 * Tests for the Parameters section on the build-detail page (issue #1266).
 *
 * Pinned invariants (from the ticket's acceptance criteria + test matrix):
 *   1. params present → one `key = value` row per param, each value visible.
 *   2. no params (undefined / null / empty object) → renders NOTHING (no empty
 *      card, no "—"); the section element is absent from the DOM entirely.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { BuildParametersSection } from './BuildParametersSection'

describe('BuildParametersSection', () => {
  it('renders one row per param with key and value visible', () => {
    render(
      <BuildParametersSection params={{ GREETING: 'world', MODE: 'dev' }} />,
    )

    expect(screen.getByTestId('build-parameters-section')).toBeTruthy()

    const greeting = screen.getByTestId('build-param-GREETING')
    expect(greeting.textContent).toContain('GREETING')
    expect(greeting.textContent).toContain('world')

    const mode = screen.getByTestId('build-param-MODE')
    expect(mode.textContent).toContain('MODE')
    expect(mode.textContent).toContain('dev')
  })

  it('renders nothing when params is undefined', () => {
    const { container } = render(<BuildParametersSection params={undefined} />)
    expect(screen.queryByTestId('build-parameters-section')).toBeNull()
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when params is null', () => {
    const { container } = render(<BuildParametersSection params={null} />)
    expect(screen.queryByTestId('build-parameters-section')).toBeNull()
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when params is an empty object (no empty card)', () => {
    const { container } = render(<BuildParametersSection params={{}} />)
    expect(screen.queryByTestId('build-parameters-section')).toBeNull()
    expect(container.firstChild).toBeNull()
  })
})

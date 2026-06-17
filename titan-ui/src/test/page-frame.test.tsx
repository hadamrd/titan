/**
 * Tests for the shared page frame (issue #1188, UX chart H1).
 *
 * <PageContainer> / <PageHeader> became load-bearing in #1188 when all 22
 * in-app routes adopted them. Before that they had ZERO consumers, which is
 * how a latent type bug (`title: ReactNode` colliding with the native HTML
 * `title` string attribute, TS2430) shipped uncaught — the pre-commit gate
 * only runs `vite build` (esbuild, no type-check), not `tsc`.
 *
 * These are adversarial-first: they assert the things that actually break a
 * sweep — the per-width max-width class (H1 framing), a single dominant <h1>
 * (H6 hierarchy / H5 one primary), graceful rendering when optional slots are
 * omitted, and that a NON-string ReactNode title renders (the regression the
 * type fix unblocks).
 */
import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import { PageContainer, PageHeader } from '../components/ui/Page'

afterEach(() => cleanup())

describe('PageContainer', () => {
  // One assertion per legal width — the frame is the whole point of H1, so a
  // width silently falling back to the wrong max-width is a real regression.
  it.each([
    ['narrow', 'max-w-3xl'],
    ['default', 'max-w-screen-xl'],
    ['wide', 'max-w-screen-2xl'],
  ] as const)('applies the %s max-width class', (width, cls) => {
    render(
      <PageContainer width={width} data-testid="pc">
        <div>body</div>
      </PageContainer>,
    )
    const el = screen.getByTestId('pc')
    expect(el.className).toContain(cls)
    // Always centred + padded so content never hugs the top-left (H1).
    expect(el.className).toContain('mx-auto')
    expect(el.className).toContain('px-6')
  })

  it('defaults to the default width when no width prop is given', () => {
    render(
      <PageContainer data-testid="pc">
        <div>body</div>
      </PageContainer>,
    )
    expect(screen.getByTestId('pc').className).toContain('max-w-screen-xl')
  })

  it('renders its children', () => {
    render(
      <PageContainer>
        <span>hello body</span>
      </PageContainer>,
    )
    expect(screen.getByText('hello body')).toBeInTheDocument()
  })
})

describe('PageHeader', () => {
  it('renders the title as the single dominant h1 (H5/H6)', () => {
    render(<PageHeader title="Builds" />)
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0].textContent).toBe('Builds')
  })

  it('renders description and actions when provided', () => {
    render(
      <PageHeader
        title="Pipelines"
        description="3 discovered jobs"
        actions={<button type="button">New pipeline</button>}
      />,
    )
    expect(screen.getByText('3 discovered jobs')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New pipeline' })).toBeInTheDocument()
  })

  // Adversarial: most converted routes pass NO description and NO actions.
  // The header must mount cleanly with just a title — no stray empty nodes,
  // no crash, still exactly one heading.
  it('mounts with title only — no description text, no action button', () => {
    render(<PageHeader title="Settings" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  // Regression for the TS2430 fix: several routes pass a RICH ReactNode title
  // (e.g. an inline logo tile + org name, or a breadcrumb), not a plain
  // string. Before the `Omit<…, 'title'>` fix this would not type-check; this
  // asserts it also renders correctly at runtime.
  it('accepts a non-string ReactNode title and renders its content', () => {
    render(
      <PageHeader
        title={
          <span>
            <img src="/logo.png" alt="acme logo" /> acme-org
          </span>
        }
      />,
    )
    const heading = screen.getByRole('heading', { level: 1 })
    expect(heading.textContent).toContain('acme-org')
    expect(screen.getByAltText('acme logo')).toBeInTheDocument()
  })

  // Adversarial: a falsy description (empty string / null / undefined) must
  // NOT render an empty muted <p> that throws off the spacing rhythm (H3).
  it.each([
    ['', 'empty string'],
    [null, 'null'],
    [undefined, 'undefined'],
  ] as [string | null | undefined, string][])(
    'renders no description paragraph when description is %s (%s)',
    (description) => {
      const { container } = render(<PageHeader title="Audit" description={description} />)
      expect(container.querySelector('p')).toBeNull()
    },
  )
})

/**
 * Render smoke-test for BuildsFilterBar (#1071).
 *
 * Covers:
 *  - Renders with the provided value (controlled input).
 *  - User types → fires onChange with the merged value.
 *  - Clear button visible only when q is non-empty; click → emits {q:''}.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { BuildsFilterBar } from '../BuildsFilterBar'

describe('BuildsFilterBar', () => {
  it('renders the controlled search value', () => {
    render(<BuildsFilterBar value={{ q: 'hello' }} onChange={() => {}} />)
    const input = screen.getByTestId('filter-search') as HTMLInputElement
    expect(input.value).toBe('hello')
  })

  it('hides the clear button when q is empty', () => {
    render(<BuildsFilterBar value={{ q: '' }} onChange={() => {}} />)
    expect(screen.queryByTestId('filter-clear')).toBeNull()
  })

  it('fires onChange with the merged value when the user types', () => {
    const onChange = vi.fn()
    render(<BuildsFilterBar value={{ q: '' }} onChange={onChange} />)
    const input = screen.getByTestId('filter-search') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'foo' } })
    expect(onChange).toHaveBeenCalledWith({ q: 'foo' })
  })

  it('emits {q:""} when the clear button is clicked', () => {
    const onChange = vi.fn()
    render(<BuildsFilterBar value={{ q: 'noise' }} onChange={onChange} />)
    fireEvent.click(screen.getByTestId('filter-clear'))
    expect(onChange).toHaveBeenCalledWith({ q: '' })
  })
})

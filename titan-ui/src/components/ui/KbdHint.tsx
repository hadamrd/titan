/**
 * Keyboard hint chip — fix #11. Two-key shortcuts like `g b` render as
 * `<kbd>g</kbd><kbd>b</kbd>`. Wrap inside `.nav-item` to inherit the
 * hover-reveal opacity transition defined in tokens.css.
 */
interface KbdHintProps {
  /** Space-separated keys, e.g. `"g b"`. */
  keys: string
  className?: string
}

export function KbdHint({ keys, className }: KbdHintProps) {
  return (
    <span className={`kbd-hint ${className ?? ''}`}>
      {keys.split(' ').map((k, i) => (
        <kbd key={i}>{k}</kbd>
      ))}
    </span>
  )
}

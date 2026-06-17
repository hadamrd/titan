export function ComingSoon({ title, detail }: { title: string; detail: string }) {
  return (
    <div
      data-testid="coming-soon"
      style={{
        padding: '24px 0 18px',
        borderTop: '1px solid var(--border)',
        marginTop: 18,
        display: 'flex',
        alignItems: 'baseline',
        gap: 12,
        flexWrap: 'wrap',
      }}
    >
      <div style={{ fontSize: 13, fontWeight: 500 }}>{title}</div>
      <div style={{ fontSize: 12, color: 'var(--fg-muted)', flex: '1 1 280px', lineHeight: 1.5 }}>
        {detail}
      </div>
      <span
        style={{
          fontSize: 10,
          fontWeight: 600,
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
          color: 'var(--fg-dim)',
        }}
      >
        coming soon
      </span>
    </div>
  )
}

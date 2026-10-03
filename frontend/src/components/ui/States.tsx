import type { ReactNode } from 'react'

/**
 * Shared loading / empty / error primitives so every screen renders these three states the same
 * way (consistency + DRY). Purely presentational; callers own the data-fetching. Styling leans on
 * the Rendra palette via inline styles so the components work inside any CSS-scoped screen.
 */

export function Spinner({ size = 22, label = 'Loading' }: { size?: number; label?: string }) {
  return (
    <span
      role="status"
      aria-label={label}
      style={{
        display: 'inline-block', width: size, height: size, borderRadius: '50%',
        border: '2px solid #E3DFD6', borderTopColor: '#FF5B2E',
        animation: 'rendra-spin .7s linear infinite',
      }}
    />
  )
}

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div style={panel} aria-busy="true">
      <Spinner />
      <p style={muted}>{label}</p>
    </div>
  )
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div style={panel}>
      <p style={{ font: "600 16px 'Bricolage Grotesque', system-ui, sans-serif", margin: 0 }}>{title}</p>
      {hint && <p style={muted}>{hint}</p>}
      {action}
    </div>
  )
}

export function ErrorState({
  title = 'Could not load this',
  message,
  onRetry,
}: {
  title?: string
  message?: string
  onRetry?: () => void
}) {
  return (
    <div style={panel} role="alert">
      <p style={{ font: "600 16px 'Bricolage Grotesque', system-ui, sans-serif", margin: 0, color: '#C63B2B' }}>
        {title}
      </p>
      {message && <p style={muted}>{message}</p>}
      {onRetry && (
        <button onClick={onRetry} style={retryBtn}>Try again</button>
      )}
    </div>
  )
}

/** A single shimmering placeholder block; compose several to build skeleton layouts. */
export function Skeleton({ width = '100%', height = 16, radius = 8 }: { width?: number | string; height?: number; radius?: number }) {
  return (
    <span
      aria-hidden="true"
      style={{
        display: 'block', width, height, borderRadius: radius,
        background: 'linear-gradient(90deg, #EFEBE3 25%, #F6F4EF 37%, #EFEBE3 63%)',
        backgroundSize: '400% 100%', animation: 'rendra-shimmer 1.4s ease infinite',
      }}
    />
  )
}

const panel = {
  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  gap: 12, padding: 40, textAlign: 'center',
} as const
const muted = { color: '#6E6B74', fontSize: 14, lineHeight: 1.5, margin: 0 } as const
const retryBtn = {
  height: 40, padding: '0 16px', borderRadius: 10, border: '1px solid #E3DFD6',
  background: '#fff', color: '#16151A', fontWeight: 600, cursor: 'pointer',
} as const

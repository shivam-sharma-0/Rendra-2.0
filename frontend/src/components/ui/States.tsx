import type { ReactNode } from 'react'
import NeuButton from '../neu/NeuButton'
import '../neu/neu.css'
import './states.css'

/**
 * Shared loading / empty / error primitives so every screen renders these three states the same
 * way. Purely presentational; callers own the data-fetching. Colours come from the neumorphic kit's
 * --neu-* tokens, so they follow light and dark mode.
 */

export function Spinner({ size = 22, label = 'Loading' }: { size?: number; label?: string }) {
  return <span role="status" aria-label={label} className="ui-spinner" style={{ width: size, height: size }} />
}

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="ui-state" aria-busy="true">
      <Spinner />
      <p className="ui-state__text">{label}</p>
    </div>
  )
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="ui-state">
      <p className="ui-state__title">{title}</p>
      {hint && <p className="ui-state__text">{hint}</p>}
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
    <div className="ui-state" role="alert">
      <p className="ui-state__title ui-state__title--error">{title}</p>
      {message && <p className="ui-state__text">{message}</p>}
      {onRetry && <NeuButton size="sm" onClick={onRetry}>Try again</NeuButton>}
    </div>
  )
}

/** A single shimmering placeholder block; compose several to build skeleton layouts. */
export function Skeleton({ width = '100%', height = 16, radius = 8 }: { width?: number | string; height?: number; radius?: number }) {
  return <span aria-hidden="true" className="ui-skeleton" style={{ width, height, borderRadius: radius }} />
}

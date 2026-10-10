import { cx } from '../../lib/cx'

export type NeuButtonStyle = {
  /** `raised` (default) for secondary actions; `primary` (solid ink) and `accent` (brand orange) for the main action; `danger` (solid red) to confirm a destructive one. */
  variant?: 'raised' | 'primary' | 'accent' | 'danger'
  /** Height: sm 34 · md 40 (default) · lg 44 · xl 54. */
  size?: 'sm' | 'md' | 'lg' | 'xl'
  /** Fully rounded ends. */
  pill?: boolean
  /** Stretch to the container's full width. */
  block?: boolean
}

/** Soft-UI button classes; use directly for elements NeuButton cannot render, such as a router `<Link>`. */
export function neuButtonClass({ variant = 'raised', size = 'md', pill = false, block = false }: NeuButtonStyle, className?: string) {
  return cx(
    'neu-btn',
    variant === 'danger' ? 'neu-btn--accent neu-btn--danger' : variant !== 'raised' && `neu-btn--${variant}`,
    size !== 'md' && `neu-btn--${size}`,
    pill && 'neu-btn--pill',
    block && 'neu-btn--block',
    className,
  )
}

import type { ReactNode } from 'react'
import { cx } from '../../lib/cx'
import { useEscape } from '../../lib/useEscape'

type MenuProps = {
  open: boolean
  onClose: () => void
  /** Accessible name of the menu. */
  label: string
  /** Which edge of the trigger the menu lines up with. */
  align?: 'start' | 'end'
  /** Open under the trigger (default) or above it, e.g. for a composer at the bottom of the screen. */
  side?: 'below' | 'above'
  className?: string
  children: ReactNode
}

/**
 * Dropdown panel under (or above) its trigger. Render it next to the trigger inside an element with
 * `position: relative` (the `neu-menu-anchor` class). A click outside or Escape closes it.
 */
export default function NeuMenu({ open, onClose, label, align = 'start', side = 'below', className, children }: MenuProps) {
  useEscape(onClose, open)
  if (!open) return null
  return (
    <>
      <div className="neu-menu-scrim" onClick={onClose} />
      <div role="menu" aria-label={label} className={cx('neu-menu', align === 'end' && 'neu-menu--end', side === 'above' && 'neu-menu--above', className)}>{children}</div>
    </>
  )
}

type ItemProps = {
  title: string
  /** Optional one-line description under the title. */
  hint?: string
  /** Optional 24×24 stroked SVG path. */
  icon?: string
  selected?: boolean
  onSelect: () => void
}

/** A menu row: optional icon, a title and an optional hint. `selected` marks the current choice. */
export function NeuMenuItem({ title, hint, icon, selected = false, onSelect }: ItemProps) {
  return (
    <button type="button" role="menuitemradio" aria-checked={selected} className={cx('neu-menu__item', selected && 'is-selected')} onClick={onSelect}>
      {icon && (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d={icon} />
        </svg>
      )}
      <span><b>{title}</b>{hint && <small>{hint}</small>}</span>
    </button>
  )
}

import type { ReactNode } from 'react'
import { cx } from '../../../lib/cx'
import { useEscape } from '../../hooks/useEscape'

type Props = {
  open: boolean
  onClose: () => void
  /** Accessible name of the menu. */
  label: string
  /** Which edge of the trigger the menu lines up with. */
  align?: 'start' | 'end'
  children: ReactNode
}

/** Soft-UI dropdown panel under its trigger. A click outside or Escape closes it. */
export default function MenuPopover({ open, onClose, label, align = 'start', children }: Props) {
  useEscape(onClose, open)
  if (!open) return null
  return (
    <>
      <div className="kap-scrim" onClick={onClose} />
      <div role="menu" aria-label={label} className={cx('db-menu', align === 'end' && 'db-menu--end')}>{children}</div>
    </>
  )
}

type ItemProps = { icon: string; title: string; hint?: string; selected?: boolean; onSelect: () => void }

/** A menu row with an icon, a title and an optional one-line hint. */
export function MenuItem({ icon, title, hint, selected = false, onSelect }: ItemProps) {
  return (
    <button type="button" role="menuitemradio" aria-checked={selected} className={cx('db-menu__item', selected && 'is-selected')} onClick={onSelect}>
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d={icon} /></svg>
      <span><b>{title}</b>{hint && <small>{hint}</small>}</span>
    </button>
  )
}

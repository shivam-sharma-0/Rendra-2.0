import type { CSSProperties } from 'react'
import { cx } from '../../lib/cx'
import './ui.css'

export type PreviewColours = { bg: string; fg: string; card: string; accent?: string }

/** A tiny abstract app screen (title bars and three tiles) in the given colours. Decorative. */
export default function MiniPreview({ colours, className }: { colours: PreviewColours; className?: string }) {
  const style = { '--mp-bg': colours.bg, '--mp-fg': colours.fg, '--mp-card': colours.card, '--mp-accent': colours.accent ?? 'transparent' } as CSSProperties
  return (
    <div className={cx('mini-preview', className)} style={style} aria-hidden>
      <div className="mini-preview__top"><span /><span /></div>
      <span className="mini-preview__bar" />
      <span className="mini-preview__bar mini-preview__bar--short" />
      <div className="mini-preview__tiles"><span /><span /><span /></div>
    </div>
  )
}

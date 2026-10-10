import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { cx } from '../../lib/cx'
import BrandMark from '../screens/landing/BrandMark'
import './centered-screen.css'

type Props = { children: ReactNode; footer?: ReactNode; busy?: boolean; className?: string }

/** A full-page soft surface with the brand at the top and one centred card (invites, sign-in hand-off). */
export default function CenteredScreen({ children, footer, busy, className }: Props) {
  return (
    <div className={cx('cs', className)}>
      <Link to="/" className="cs-brand"><BrandMark className="cs-brand__mark" />Rendra</Link>
      <main className="cs-card neu-panel" aria-busy={busy || undefined}>{children}</main>
      {footer && <footer className="cs-foot">{footer}</footer>}
    </div>
  )
}

import type { ReactNode } from 'react'
import { cx } from '../../lib/cx'

/** Inset message box. Error alerts are announced to screen readers. */
export default function NeuAlert({ children, className }: { children: ReactNode; className?: string }) {
  return <p role="alert" className={cx('neu-well neu-alert neu-alert--error', className)}>{children}</p>
}

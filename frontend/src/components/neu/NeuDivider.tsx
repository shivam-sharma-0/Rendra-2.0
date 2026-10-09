import type { ReactNode } from 'react'
import { cx } from '../../lib/cx'

/** Engraved horizontal rule, with an optional centred label such as "OR". */
export default function NeuDivider({ children, className }: { children?: ReactNode; className?: string }) {
  return <div role="separator" className={cx('neu-divider', className)}>{children}</div>
}

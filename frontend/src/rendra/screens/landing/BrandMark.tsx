import { cx } from '../../../lib/cx'

/** Rendra's spinning spectrum mark. Size it with CSS on the passed class. */
export default function BrandMark({ className }: { className?: string }) {
  return <span className={cx('k-mark', className)} aria-hidden><i /></span>
}

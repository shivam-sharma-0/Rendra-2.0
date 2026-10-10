import type { SelectHTMLAttributes } from 'react'
import { cx } from '../../lib/cx'
import { AppIcon } from '../icons'

type Props = SelectHTMLAttributes<HTMLSelectElement> & {
  /** Accessible name when there is no visible label next to it. */
  'aria-label'?: string
  /** 34px tall instead of 42px. */
  compact?: boolean
}

/** A native select drawn as an inset well with its own chevron, matching NeuTextField. */
export default function NeuSelect({ className, compact = false, ...rest }: Props) {
  return (
    <span className={cx('neu-select', compact && 'neu-select--sm', className)}>
      <select {...rest} />
      <AppIcon name="chevronDown" />
    </span>
  )
}

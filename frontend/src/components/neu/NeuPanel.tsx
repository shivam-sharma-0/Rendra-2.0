import type { HTMLAttributes } from 'react'
import { cx } from '../../lib/cx'

type Props = HTMLAttributes<HTMLElement> & {
  /** Element to render; defaults to `div`. */
  as?: 'div' | 'section' | 'article' | 'nav' | 'aside'
  /** `sm` uses a shorter shadow and radius, for small cards. */
  size?: 'md' | 'sm'
  /** Lifts slightly on hover; use for cards that respond to the pointer. */
  interactive?: boolean
}

/** Raised surface that other soft-UI controls sit on. */
export default function NeuPanel({ as: Tag = 'div', size = 'md', interactive = false, className, ...rest }: Props) {
  return <Tag className={cx('neu-panel', size === 'sm' && 'neu-panel--sm', interactive && 'neu-panel--interactive', className)} {...rest} />
}

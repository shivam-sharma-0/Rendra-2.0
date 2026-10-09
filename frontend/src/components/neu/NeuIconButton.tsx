import type { ButtonHTMLAttributes } from 'react'
import { cx } from '../../lib/cx'

type Props = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> & {
  /** Required: an icon-only button has no visible text to name it. */
  'aria-label': string
  /** Diameter: sm 32 (default) · md 40 · lg 44. */
  size?: 'sm' | 'md' | 'lg'
  /** `accent` fills it with the brand colour, for a main action such as "send". */
  variant?: 'raised' | 'accent'
}

/** Round icon-only button (close, attach, send, etc.). */
export default function NeuIconButton({ className, size = 'sm', variant = 'raised', type = 'button', ...rest }: Props) {
  return (
    <button
      type={type}
      className={cx('neu-icon-btn', size !== 'sm' && `neu-icon-btn--${size}`, variant === 'accent' && 'neu-icon-btn--accent', className)}
      {...rest}
    />
  )
}

import type { ButtonHTMLAttributes } from 'react'

type Variant = 'primary' | 'outline' | 'ghost'
type Size = 'sm' | 'md'
type Props = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: Size }

const base =
  'inline-flex items-center justify-center gap-2 rounded-[10px] font-semibold transition-all duration-150 ' +
  'active:scale-[.985] focus:outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--ring)] focus-visible:ring-offset-2 focus-visible:ring-offset-[color:var(--bg-solid)] ' +
  'disabled:pointer-events-none disabled:opacity-60'

const sizes: Record<Size, string> = {
  sm: 'px-3.5 py-2 text-[13px]',
  md: 'px-4.5 py-2.5 text-sm',
}

const variants: Record<Variant, string> = {
  primary:
    'text-[color:var(--spark-contrast)] bg-[color:var(--spark)] shadow-[var(--shadow-pop)] ' +
    'hover:bg-[color:var(--spark-hover)] active:bg-[color:var(--spark-hover)]',
  outline: 'text-text border border-[color:var(--border-strong)] hover:bg-black/[0.03] dark:hover:bg-white/[0.05]',
  ghost: 'text-muted hover:text-text hover:bg-black/[0.03] dark:hover:bg-white/[0.05]',
}

export default function Button({ variant = 'primary', size = 'md', className = '', ...props }: Props) {
  return <button className={`${base} ${sizes[size]} ${variants[variant]} ${className}`} {...props} />
}

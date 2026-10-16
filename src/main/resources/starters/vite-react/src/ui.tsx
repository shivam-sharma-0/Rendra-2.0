import { useId } from 'react'
import type {
  ButtonHTMLAttributes,
  HTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react'

// Tested building blocks styled from the theme tokens in index.css. Every component passes extra props
// (id, data-*, aria-*, handlers) through to its element, so acceptance hooks keep working.

export function cx(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ')
}

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'sm' | 'md'
}

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-accent-foreground hover:opacity-90',
  secondary: 'border border-border bg-surface text-foreground hover:bg-border/50',
  ghost: 'text-foreground hover:bg-surface',
  danger: 'bg-red-600 text-white hover:bg-red-700',
}

export function Button({ variant = 'primary', size = 'md', type = 'button', className, ...props }: ButtonProps) {
  return (
    <button
      type={type}
      className={cx(
        'inline-flex items-center justify-center gap-2 rounded-ui font-medium transition',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
        'disabled:cursor-not-allowed disabled:opacity-50',
        size === 'sm' ? 'h-8 px-3 text-sm' : 'h-10 px-4',
        BUTTON_VARIANTS[variant],
        className,
      )}
      {...props}
    />
  )
}

interface FieldProps {
  label: string
  hint?: string
  error?: string
}

const CONTROL =
  'w-full min-w-0 rounded-ui border border-border bg-background px-3 py-2 text-foreground ' +
  'focus:outline-2 focus:outline-accent aria-[invalid=true]:border-red-600'

function Field({ id, label, hint, error, children }: FieldProps & { id: string; children: ReactNode }) {
  return (
    <div className="grid gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${id}-message`} className="text-sm text-red-600">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-message`} className="text-sm text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

function useFieldId(id: string | undefined): string {
  const generated = useId()
  return id ?? generated
}

export interface InputProps extends InputHTMLAttributes<HTMLInputElement>, FieldProps {}

export function Input({ id, label, hint, error, className, ...props }: InputProps) {
  const fieldId = useFieldId(id)
  return (
    <Field id={fieldId} label={label} hint={hint} error={error}>
      <input
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? `${fieldId}-message` : undefined}
        className={cx(CONTROL, className)}
        {...props}
      />
    </Field>
  )
}

export interface TextAreaProps extends TextareaHTMLAttributes<HTMLTextAreaElement>, FieldProps {}

export function TextArea({ id, label, hint, error, className, ...props }: TextAreaProps) {
  const fieldId = useFieldId(id)
  return (
    <Field id={fieldId} label={label} hint={hint} error={error}>
      <textarea
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? `${fieldId}-message` : undefined}
        className={cx(CONTROL, 'min-h-24', className)}
        {...props}
      />
    </Field>
  )
}

export interface SelectOption {
  value: string
  label: string
}

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement>, FieldProps {
  options: SelectOption[]
}

export function Select({ id, label, hint, error, options, className, ...props }: SelectProps) {
  const fieldId = useFieldId(id)
  return (
    <Field id={fieldId} label={label} hint={hint} error={error}>
      <select
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? `${fieldId}-message` : undefined}
        className={cx(CONTROL, className)}
        {...props}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </Field>
  )
}

export interface CardProps extends Omit<HTMLAttributes<HTMLElement>, 'title'> {
  title?: string
  actions?: ReactNode
  children: ReactNode
  className?: string
}

export function Card({ title, actions, children, className, ...props }: CardProps) {
  return (
    <section {...props} className={cx('min-w-0 rounded-ui border border-border bg-background p-4 shadow-sm', className)}>
      {title || actions ? (
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          {title ? <h2 className="text-lg font-semibold">{title}</h2> : <span />}
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  )
}

export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger'

const BADGE_TONES: Record<BadgeTone, string> = {
  neutral: 'bg-surface text-muted',
  accent: 'bg-accent/10 text-accent',
  success: 'bg-green-100 text-green-800',
  warning: 'bg-amber-100 text-amber-800',
  danger: 'bg-red-100 text-red-800',
}

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone
}

export function Badge({ tone = 'neutral', className, children, ...props }: BadgeProps) {
  return (
    <span {...props} className={cx('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium', BADGE_TONES[tone], className)}>
      {children}
    </span>
  )
}

export interface EmptyStateProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title: string
  description?: string
  action?: ReactNode
}

export function EmptyState({ title, description, action, className, ...props }: EmptyStateProps) {
  return (
    <div {...props} className={cx('grid justify-items-center gap-2 rounded-ui border border-dashed border-border p-8 text-center', className)}>
      <p className="font-medium">{title}</p>
      {description ? <p className="text-sm text-muted">{description}</p> : null}
      {action}
    </div>
  )
}

export interface PageShellProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title: string
  nav?: ReactNode
  actions?: ReactNode
  children: ReactNode
}

/** A responsive page: a header that wraps instead of overflowing on phones, and a centred content column. */
export function PageShell({ title, nav, actions, children, className, ...props }: PageShellProps) {
  return (
    <div {...props} className={cx('min-h-screen bg-background text-foreground', className)}>
      <header className="border-b border-border">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <p className="text-lg font-semibold">{title}</p>
          {nav ? <nav className="flex min-w-0 flex-wrap gap-4 text-sm">{nav}</nav> : null}
          {actions ? <div className="ml-auto flex flex-wrap gap-2">{actions}</div> : null}
        </div>
      </header>
      <main className="mx-auto grid max-w-5xl gap-6 px-4 py-6">{children}</main>
    </div>
  )
}

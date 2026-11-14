import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react'

type Labelled = { label: ReactNode; hint?: ReactNode; error?: ReactNode }

export function Button({ variant = 'primary', size = 'md', className = '', type = 'button', ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'ghost' | 'danger'; size?: 'sm' | 'md' | 'lg' }) {
  return <button type={type} className={`app-button app-button--${variant} app-button--${size} ${className}`.trim()} {...rest} />
}

export function Field({ label, hint, error, children }: Labelled & { children: ReactNode }) {
  return <label className="app-field">
    <span className="app-field__label">{label}</span>
    {children}
    {hint && !error && <span className="app-field__hint">{hint}</span>}
    {error && <span className="app-field__error" role="alert">{error}</span>}
  </label>
}

export function TextField({ label, hint, error, ...input }: Labelled & InputHTMLAttributes<HTMLInputElement>) {
  return <Field label={label} hint={hint} error={error}><input {...input} aria-invalid={error ? true : undefined} /></Field>
}

export function TextArea({ label, hint, error, ...area }: Labelled & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <Field label={label} hint={hint} error={error}><textarea {...area} aria-invalid={error ? true : undefined} /></Field>
}

export function SelectField({ label, hint, error, options, ...select }: Labelled & SelectHTMLAttributes<HTMLSelectElement> & {
  options: { value: string; label: string }[]
}) {
  return <Field label={label} hint={hint} error={error}>
    <select {...select} aria-invalid={error ? true : undefined}>
      {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>
  </Field>
}

export function SearchInput({ label = 'Search', ...input }: InputHTMLAttributes<HTMLInputElement> & { label?: string }) {
  return <label className="app-search">
    <span className="app-visually-hidden">{label}</span>
    <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
    <input type="search" placeholder={label} {...input} />
  </label>
}

export function FilterChips<T extends string>({ options, value, onChange, label = 'Filter' }: {
  options: { value: T; label: string }[]; value: T; onChange: (value: T) => void; label?: string
}) {
  return <div className="app-chips" role="group" aria-label={label}>
    {options.map(option => <button key={option.value} type="button" className="app-chip" aria-pressed={option.value === value}
      onClick={() => onChange(option.value)}>{option.label}</button>)}
  </div>
}

export function Tabs<T extends string>({ tabs, value, onChange, label = 'Sections' }: {
  tabs: { value: T; label: string }[]; value: T; onChange: (value: T) => void; label?: string
}) {
  return <div className="app-tabs" role="tablist" aria-label={label}>
    {tabs.map(tab => <button key={tab.value} type="button" role="tab" className="app-tab" aria-selected={tab.value === value}
      onClick={() => onChange(tab.value)}>{tab.label}</button>)}
  </div>
}

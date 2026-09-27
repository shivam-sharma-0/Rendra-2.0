import { forwardRef, type InputHTMLAttributes } from 'react'

type FieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string
}

/**
 * Labeled text input with inline validation error and a11y wiring (aria-invalid + aria-describedby).
 * Works with react-hook-form's `register` (forwards ref + name/onChange/onBlur). Reused by every
 * form (auth, onboarding, settings) so field markup and error display stay consistent.
 */
const Field = forwardRef<HTMLInputElement, FieldProps>(function Field(
  { label, error, id, name, ...rest },
  ref,
) {
  const fieldId = id ?? name
  const errorId = fieldId ? `${fieldId}-error` : undefined
  return (
    <label htmlFor={fieldId} className="k-field">
      <span className="k-field-label">{label}</span>
      <input
        id={fieldId}
        name={name}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className="k-field-input"
        {...rest}
      />
      {error && (
        <span id={errorId} className="k-field-error" role="alert">
          {error}
        </span>
      )}
    </label>
  )
})

export default Field

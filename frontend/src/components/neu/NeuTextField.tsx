import { forwardRef, useId, type InputHTMLAttributes } from 'react'
import { cx } from '../../lib/cx'

type Props = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string
}

/**
 * Labelled, inset text input with an inline validation error and its a11y wiring
 * (aria-invalid + aria-describedby). Forwards the ref, so react-hook-form's
 * `register()` can be spread straight onto it.
 */
const NeuTextField = forwardRef<HTMLInputElement, Props>(function NeuTextField(
  { label, error, id, className, ...rest },
  ref,
) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const errorId = `${inputId}-error`

  return (
    <div className={cx('neu-field', className)}>
      <label htmlFor={inputId} className="neu-field__label">{label}</label>
      <input
        ref={ref}
        id={inputId}
        className="neu-input"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        {...rest}
      />
      {error && <span id={errorId} className="neu-field__error" role="alert">{error}</span>}
    </div>
  )
})

export default NeuTextField

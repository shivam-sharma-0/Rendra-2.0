import type { FormEvent } from 'react'
import type { UseFormReturn } from 'react-hook-form'
import { NeuAlert, NeuButton, NeuTextField } from '../../../components/neu'
import { Ico } from '../../parts'
import type { AuthMode, AuthValues } from './authSchema'
import { ICON } from '../../icons'

type Props = {
  mode: AuthMode
  form: UseFormReturn<AuthValues>
  formError: string
  onBack: () => void
  onSubmit: (e: FormEvent) => void
}

/** Step 2: password (plus name on signup) for the email chosen in step 1. */
export default function PasswordStep({ mode, form, formError, onBack, onSubmit }: Props) {
  const isSignup = mode === 'signup'
  const { errors, isSubmitting } = form.formState
  const email = form.getValues('email')

  return (
    <form className="kau-step" onSubmit={onSubmit} noValidate>
      <button type="button" className="kau-back" onClick={onBack}>
        <Ico d={ICON.arrowLeft} size={14} sw={2.2} /> Back
      </button>

      <div className="kau-email neu-well">
        <span>{email}</span>
        <NeuButton className="kau-email__change" onClick={onBack}>Change</NeuButton>
      </div>
      {/* Lets password managers pair the saved password with this email. */}
      <input type="email" name="username" autoComplete="username" value={email} readOnly hidden />

      {isSignup && (
        <NeuTextField label="Your name" placeholder="Alex Morgan" autoComplete="name" autoFocus
          error={errors.name?.message} {...form.register('name')} />
      )}
      <NeuTextField label="Password" type="password" autoFocus={!isSignup}
        placeholder={isSignup ? 'At least 8 characters' : 'Your password'}
        autoComplete={isSignup ? 'new-password' : 'current-password'}
        error={errors.password?.message} {...form.register('password')} />

      {formError && <NeuAlert>{formError}</NeuAlert>}
      <NeuButton type="submit" variant="primary" size="lg" block disabled={isSubmitting}>
        {isSubmitting ? 'Please wait…' : isSignup ? 'Create account' : 'Log in'} <Ico d={ICON.arrowRight} size={16} sw={2.2} />
      </NeuButton>

      <span className="kau-note">
        <Ico d={ICON.lock} size={14} sw={2} /> Your account is protected with secure session cookies.
      </span>
    </form>
  )
}

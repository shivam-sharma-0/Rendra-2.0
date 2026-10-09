import type { FormEvent } from 'react'
import type { UseFormReturn } from 'react-hook-form'
import { NeuAlert, NeuButton, NeuDivider, NeuTextField } from '../../../components/neu'
import { Ico } from '../../parts'
import type { AuthMode, AuthValues } from './authSchema'
import { ICON } from '../../icons'
import SocialProviders from './SocialProviders'

type Props = {
  mode: AuthMode
  form: UseFormReturn<AuthValues>
  formError: string
  onContinue: () => void
}

/** Step 1: social sign-in, or an email address to continue with. */
export default function EmailStep({ mode, form, formError, onContinue }: Props) {
  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    onContinue()
  }

  return (
    <div className="kau-step">
      <SocialProviders />
      <NeuDivider>OR</NeuDivider>
      <form className="kau-step" onSubmit={onSubmit} noValidate>
        <NeuTextField label="Work email" type="email" placeholder="you@company.com" autoComplete="email"
          error={form.formState.errors.email?.message} {...form.register('email')} />
        {formError && <NeuAlert>{formError}</NeuAlert>}
        <NeuButton type="submit" variant="primary" size="lg" block>
          {mode === 'signup' ? 'Create account' : 'Continue with email'} <Ico d={ICON.arrowRight} size={16} sw={2.2} />
        </NeuButton>
      </form>
    </div>
  )
}

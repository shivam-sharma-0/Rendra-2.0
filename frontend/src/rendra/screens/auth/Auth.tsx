import { NeuSegmentedControl, type SegmentOption } from '../../../components/neu'
import type { AuthMode } from './authSchema'
import AuthDialog from './AuthDialog'
import AuthFooter from './AuthFooter'
import EmailStep from './EmailStep'
import PasswordStep from './PasswordStep'
import { useAuthFlow } from './useAuthFlow'
import './auth.css'

const MODES: readonly SegmentOption<AuthMode>[] = [
  { value: 'login', label: 'Log in' },
  { value: 'signup', label: 'Sign up' },
]

const TITLES: Record<AuthMode, string> = {
  login: 'Log in to your account',
  signup: 'Create free account',
}

/** Log in / sign up dialog shown over the landing page at /auth, /login and /signup. */
export default function Auth() {
  const flow = useAuthFlow()

  return (
    <AuthDialog title={TITLES[flow.mode]} onClose={flow.close} footer={<AuthFooter mode={flow.mode} />}>
      <NeuSegmentedControl aria-label="Account" className="kau-modes" options={MODES} value={flow.mode} onChange={flow.switchMode} />

      {flow.step === 'email' ? (
        <EmailStep mode={flow.mode} form={flow.form} formError={flow.formError} onContinue={() => void flow.continueWithEmail()} />
      ) : (
        <PasswordStep mode={flow.mode} form={flow.form} formError={flow.formError} onBack={flow.backToEmail} onSubmit={(e) => void flow.submit(e)} />
      )}
    </AuthDialog>
  )
}

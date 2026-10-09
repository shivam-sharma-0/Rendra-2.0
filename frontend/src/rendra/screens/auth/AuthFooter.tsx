import { Ico } from '../../parts'
import type { AuthMode } from './authSchema'
import { ICON } from '../../icons'

/** Legal and SSO notes under the form. Terms are shown only when creating an account. */
export default function AuthFooter({ mode }: { mode: AuthMode }) {
  return (
    <>
      {mode === 'signup' && (
        <span>By continuing, you agree to the <a href="#terms">Terms of Service</a> and <a href="#privacy">Privacy Policy</a>.</span>
      )}
      <span className="kau-sso">
        <Ico d={ICON.lock} size={12} sw={2} />
        <span>SSO available on <a href="#plans">Business and Enterprise</a> plans</span>
      </span>
    </>
  )
}

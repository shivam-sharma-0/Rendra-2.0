import { useMemo, useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth } from '../../auth/AuthContext'
import { googleLoginUrl } from '../../auth/api'
import { startProject, type ProjectType } from '../../api/projects'
import './auth.css'

/**
 * Log in / Sign up — pixel-faithful to Figma "03 · Log in Sign up" (scoped .kau).
 *
 * The visible screen matches Figma exactly: providers + a single "Work email" field. Since the
 * backend uses email + password (no magic-link infra), we use an email-first flow — entering the
 * email advances to a compact password step. This keeps the initial view short (no scrollbar, no
 * logo crowding) and identical to the mock, while staying functional. Only Google OAuth is wired.
 */

const loginSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
  name: z.string().optional(),
  password: z.string().min(1, 'Password is required'),
})
const signupSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
  name: z.string().max(100, 'Name is too long').optional(),
  password: z.string().min(8, 'At least 8 characters'),
})
type AuthValues = z.infer<typeof signupSchema>

const PROVIDERS = [
  { name: 'Google', mono: 'G', badge: 'google', lastUsed: true, enabled: true },
  { name: 'GitHub', mono: 'GH', badge: 'github', lastUsed: false, enabled: false },
  { name: 'Apple', mono: 'A', badge: 'apple', lastUsed: false, enabled: false },
] as const



const Arrow = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
)

export default function Auth() {
  const nav = useNavigate()
  const loc = useLocation()
  const { login, signup } = useAuth()
  const [mode, setMode] = useState<'login' | 'signup'>(loc.pathname === '/signup' ? 'signup' : 'login')
  const [stage, setStage] = useState<'email' | 'password'>('email')
  const [formError, setFormError] = useState('')
  const isSignup = mode === 'signup'

  const {
    register,
    handleSubmit,
    trigger,
    getValues,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<AuthValues>({
    resolver: zodResolver(useMemo(() => (isSignup ? signupSchema : loginSchema), [isSignup])),
    mode: 'onBlur',
  })

  const switchMode = (next: 'login' | 'signup') => {
    setMode(next)
    setStage('email')
    setFormError('')
    reset()
  }

  const toPassword = async () => {
    setFormError('')
    if (await trigger('email')) setStage('password')
  }

  const onSubmit = handleSubmit(async (values) => {
    setFormError('')
    try {
      if (isSignup) await signup(values.email, values.password, values.name || undefined)
      else await login(values.email, values.password)

      const pendingPrompt = sessionStorage.getItem('kiln_pending_prompt')
      if (pendingPrompt) {
        const pendingMode = (sessionStorage.getItem('kiln_pending_mode') || 'BUILD') as ProjectType
        sessionStorage.removeItem('kiln_pending_prompt')
        sessionStorage.removeItem('kiln_pending_mode')
        const project = await startProject(pendingPrompt, pendingMode)
        nav(`/project/${project.id}`)
      } else {
        nav(isSignup ? '/onboarding/about-you' : '/dashboard')
      }
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not sign in. Please try again.')
    }
  })

  return (
    <div className="kau">
      <section className="kau-form">
        <button type="button" className="kau-close" onClick={() => nav('/')} aria-label="Close">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6L6 18M6 6l12 12" /></svg>
        </button>
        <Link to="/" className="kau-brand"><span className="kau-brand-orb"><i /></span></Link>

        <form className="kau-center" onSubmit={onSubmit} noValidate>

          <div className="kau-head">
            <span className="kau-kicker">Start building.</span>
            <h1>{isSignup ? 'Create free account' : 'Log in to your account'}</h1>
          </div>

          {stage === 'email' ? (
            <>
              <div className="kau-providers">
                {PROVIDERS.map((p) =>
                  p.enabled ? (
                    <a key={p.name} className="kau-provider" href={googleLoginUrl()}>
                      <span className={`kau-badge ${p.badge}`}>{p.mono}</span>
                      <span>Continue with {p.name}</span>
                      {p.lastUsed && <span className="kau-lastused">Last used</span>}
                    </a>
                  ) : (
                    <button key={p.name} type="button" className="kau-provider" disabled title="Coming soon">
                      <span className={`kau-badge ${p.badge}`}>{p.mono}</span>
                      <span>Continue with {p.name}</span>
                      <span className="kau-soon">Soon</span>
                    </button>
                  ),
                )}
              </div>

              <div className="kau-or">OR</div>

              <div className="kau-fields">
                <div className="kau-field">
                  <label htmlFor="email">Work email</label>
                  <input id="email" type="email" className="kau-input" placeholder="you@company.com" autoComplete="email"
                    aria-invalid={errors.email ? true : undefined}
                    onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void toPassword() } }}
                    {...register('email')} />
                  {errors.email && <span className="kau-field-error" role="alert">{errors.email.message}</span>}
                </div>
                {formError && <p className="kau-form-error" role="alert">{formError}</p>}
                <button type="button" className="kau-cta" onClick={() => void toPassword()}>
                  {isSignup ? 'Create account' : 'Continue with email'} <Arrow />
                </button>
                <div style={{textAlign: 'center', fontSize: '13px', color: 'var(--faint)'}}>
                  {isSignup ? (
                    <>Already have an account? <a href="#" onClick={(e) => { e.preventDefault(); switchMode('login'); }} style={{color: 'var(--ink)', fontWeight: 600}}>Log in</a></>
                  ) : (
                    <>Don't have an account? <a href="#" onClick={(e) => { e.preventDefault(); switchMode('signup'); }} style={{color: 'var(--ink)', fontWeight: 600}}>Sign up</a></>
                  )}
                </div>
              </div>
            </>
          ) : (
            <div className="kau-fields">
              <button type="button" className="kau-backlink" onClick={() => { setStage('email'); setFormError('') }}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5M11 18l-6-6 6-6" /></svg>
                Back
              </button>
              <div className="kau-emailrow">
                <span>{getValues('email')}</span>
                <button type="button" onClick={() => setStage('email')}>Change</button>
              </div>
              {isSignup && (
                <div className="kau-field">
                  <label htmlFor="name">Your name</label>
                  <input id="name" className="kau-input" placeholder="Alex Morgan" autoComplete="name" autoFocus
                    aria-invalid={errors.name ? true : undefined} {...register('name')} />
                  {errors.name && <span className="kau-field-error" role="alert">{errors.name.message}</span>}
                </div>
              )}
              <div className="kau-field">
                <label htmlFor="password">Password</label>
                <input id="password" type="password" className="kau-input" autoFocus={!isSignup}
                  placeholder={isSignup ? 'At least 8 characters' : 'Your password'}
                  autoComplete={isSignup ? 'new-password' : 'current-password'}
                  aria-invalid={errors.password ? true : undefined} {...register('password')} />
                {errors.password && <span className="kau-field-error" role="alert">{errors.password.message}</span>}
              </div>
              {formError && <p className="kau-form-error" role="alert">{formError}</p>}
              <button className="kau-cta" disabled={isSubmitting}>
                {isSubmitting ? 'Please wait…' : isSignup ? 'Create account' : 'Log in'} <Arrow />
              </button>
              <span className="kau-note">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
                Your account is protected with secure session cookies.
              </span>
            </div>
          )}
        </form>

        <div className="kau-footer" style={{textAlign: 'center', marginTop: '16px'}}>
          {isSignup && <span>By continuing, you agree to the <a href="#terms">Terms of Service</a> and <a href="#privacy">Privacy Policy</a>.</span>}
          <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></svg>
            <span>SSO available on <a href="#plans">Business and Enterprise</a> plans</span>
          </span>
        </div>
      </section>

    </div>
  )
}

import { useMemo, useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth } from '../../auth/AuthContext'
import { clearLoggedOutMarker, googleLoginUrl } from '../../auth/api'
import { startProject, type ProjectType } from '../../api/projects'

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

const BADGE_COLORS: Record<string, string> = {
  google: 'bg-[#FFEDE6] text-[#B23A12]',
  github: 'bg-[#16151A] text-[#F6F4EF]',
  apple: 'bg-[#F1EEE8] text-[#16151A]',
}

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
    <div className="kau fixed inset-0 bg-black/40 z-[999] flex items-center justify-center text-[var(--ink)] font-['Geist',system-ui,sans-serif] animate-[kau-fade_.26s_ease_both] backdrop-blur-[2px]">
      <section className="w-[440px] p-10 flex flex-col bg-[var(--card)] rounded-[20px] shadow-[0_30px_60px_-15px_rgba(0,0,0,.3)] relative max-h-[95vh] overflow-y-auto max-[600px]:w-[90%] max-[600px]:p-7">
        <button type="button" className="absolute right-5 top-5 w-8 h-8 rounded-2xl border-none bg-transparent text-[var(--faint)] text-xl flex items-center justify-center hover:bg-[var(--bg)] hover:text-[var(--ink)]" onClick={() => nav('/')} aria-label="Close">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6L6 18M6 6l12 12" /></svg>
        </button>
        <Link to="/" className="flex items-center gap-2.5"><span className="w-[30px] h-[30px] rounded-xl grid place-items-center bg-[conic-gradient(from_0deg,#FF5B2E,#FFB23E,#5B4CF5,#FF5B2E)] animate-[kau-spin_7s_linear_infinite]"><i className="w-3 h-3 rounded-full bg-[var(--card)]" /></span></Link>

        <form className="grow flex flex-col justify-center gap-5" onSubmit={onSubmit} noValidate>

          <div className="flex flex-col mt-3.5 gap-1">
            <span className="text-lg font-semibold text-[#B9B6AE]">Start building.</span>
            <h1 className="m-0 font-['Geist',system-ui,sans-serif] font-bold text-xl leading-[1.2] tracking-[-0.02em]">{isSignup ? 'Create free account' : 'Log in to your account'}</h1>
          </div>

          {stage === 'email' ? (
            <>
              <div className="flex flex-col gap-2">
                {PROVIDERS.map((p) =>
                  p.enabled ? (
                    <a key={p.name} className="relative h-11 rounded-xl border border-[var(--line-2)] bg-[var(--card)] text-[var(--ink)] text-sm font-medium flex items-center justify-center gap-2.5 hover:bg-[var(--bg)] hover:border-[#CFCAC0]" href={googleLoginUrl()} onClick={clearLoggedOutMarker}>
                      <span className={`w-6 h-6 rounded-[7px] text-xs font-bold grid place-items-center ${BADGE_COLORS[p.badge]}`}>{p.mono}</span>
                      <span>Continue with {p.name}</span>
                      {p.lastUsed && <span className="absolute right-3.5 text-[11px] font-semibold py-1 px-2 rounded-lg bg-[var(--violet-soft)] text-[var(--violet-ink)]">Last used</span>}
                    </a>
                  ) : (
                    <button key={p.name} type="button" className="relative h-11 rounded-xl border border-[var(--line-2)] bg-[var(--card)] text-[var(--ink)] text-sm font-medium flex items-center justify-center gap-2.5 hover:bg-[var(--bg)] hover:border-[#CFCAC0] disabled:cursor-not-allowed disabled:opacity-[.62]" disabled title="Coming soon">
                      <span className={`w-6 h-6 rounded-[7px] text-xs font-bold grid place-items-center ${BADGE_COLORS[p.badge]}`}>{p.mono}</span>
                      <span>Continue with {p.name}</span>
                      <span className="absolute right-3.5 text-[11px] text-[var(--dim)]">Soon</span>
                    </button>
                  ),
                )}
              </div>

              <div className="flex items-center gap-3.5 text-[#8A8791] text-[11px] font-semibold tracking-[0.08em] -my-0.5 before:content-[''] before:grow before:h-px before:bg-[var(--line)] after:content-[''] after:grow after:h-px after:bg-[var(--line)]">OR</div>

              <div className="flex flex-col gap-2.5">
                <div className="flex flex-col gap-1.5">
                  <label htmlFor="email" className="text-[12.5px] font-semibold text-[var(--muted)]">Work email</label>
                  <input id="email" type="email" className="kau-input h-11 px-3.5 rounded-xl border border-[var(--line-2)] bg-[#FBFAF7] text-sm text-[var(--ink)] outline-[var(--violet)] aria-[invalid=true]:border-[#C63B2B] aria-[invalid=true]:bg-[#FDF3F1]" placeholder="you@company.com" autoComplete="email"
                    aria-invalid={errors.email ? true : undefined}
                    onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void toPassword() } }}
                    {...register('email')} />
                  {errors.email && <span className="text-[#C63B2B] text-xs font-medium" role="alert">{errors.email.message}</span>}
                </div>
                {formError && <p className="m-0 text-[#C63B2B] text-[13px]" role="alert">{formError}</p>}
                <button type="button" className="kau-cta h-[46px] rounded-xl bg-[var(--ink)] text-[var(--bg)] text-sm font-semibold flex items-center justify-center gap-2 border-none shadow-[0_14px_26px_-14px_rgba(22,21,26,.6)] disabled:opacity-60 disabled:cursor-not-allowed" onClick={() => void toPassword()}>
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
            <div className="flex flex-col gap-2.5">
              <button type="button" className="self-start border-none bg-transparent text-[var(--faint)] text-[13px] font-semibold flex items-center gap-1.5 p-0" onClick={() => { setStage('email'); setFormError('') }}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5M11 18l-6-6 6-6" /></svg>
                Back
              </button>
              <div className="flex items-center justify-between h-11 px-3.5 rounded-xl border border-[var(--line-2)] bg-[#FBFAF7] text-sm text-[var(--muted)]">
                <span>{getValues('email')}</span>
                <button type="button" className="border-none bg-transparent text-[var(--violet-ink)] text-[13px] font-semibold p-0" onClick={() => setStage('email')}>Change</button>
              </div>
              {isSignup && (
                <div className="flex flex-col gap-1.5">
                  <label htmlFor="name" className="text-[12.5px] font-semibold text-[var(--muted)]">Your name</label>
                  <input id="name" className="kau-input h-11 px-3.5 rounded-xl border border-[var(--line-2)] bg-[#FBFAF7] text-sm text-[var(--ink)] outline-[var(--violet)] aria-[invalid=true]:border-[#C63B2B] aria-[invalid=true]:bg-[#FDF3F1]" placeholder="Alex Morgan" autoComplete="name" autoFocus
                    aria-invalid={errors.name ? true : undefined} {...register('name')} />
                  {errors.name && <span className="text-[#C63B2B] text-xs font-medium" role="alert">{errors.name.message}</span>}
                </div>
              )}
              <div className="flex flex-col gap-1.5">
                <label htmlFor="password" className="text-[12.5px] font-semibold text-[var(--muted)]">Password</label>
                <input id="password" type="password" className="kau-input h-11 px-3.5 rounded-xl border border-[var(--line-2)] bg-[#FBFAF7] text-sm text-[var(--ink)] outline-[var(--violet)] aria-[invalid=true]:border-[#C63B2B] aria-[invalid=true]:bg-[#FDF3F1]" autoFocus={!isSignup}
                  placeholder={isSignup ? 'At least 8 characters' : 'Your password'}
                  autoComplete={isSignup ? 'new-password' : 'current-password'}
                  aria-invalid={errors.password ? true : undefined} {...register('password')} />
                {errors.password && <span className="text-[#C63B2B] text-xs font-medium" role="alert">{errors.password.message}</span>}
              </div>
              {formError && <p className="m-0 text-[#C63B2B] text-[13px]" role="alert">{formError}</p>}
              <button className="kau-cta h-[46px] rounded-xl bg-[var(--ink)] text-[var(--bg)] text-sm font-semibold flex items-center justify-center gap-2 border-none shadow-[0_14px_26px_-14px_rgba(22,21,26,.6)] disabled:opacity-60 disabled:cursor-not-allowed" disabled={isSubmitting}>
                {isSubmitting ? 'Please wait…' : isSignup ? 'Create account' : 'Log in'} <Arrow />
              </button>
              <span className="text-[13px] text-[var(--faint)] flex items-center gap-2">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
                Your account is protected with secure session cookies.
              </span>
            </div>
          )}
        </form>

        <div className="flex flex-col gap-2 text-xs text-[var(--faint)] leading-[1.4] pt-1 [&_a]:underline" style={{textAlign: 'center', marginTop: '16px'}}>
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

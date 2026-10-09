import { useCallback, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useAuth } from '../../../auth/AuthContext'
import { schemaFor, type AuthMode, type AuthStep, type AuthValues } from './authSchema'
import { resolvePostAuthPath } from './postAuthRedirect'

const SIGNUP_PATH = '/signup'
const LOGIN_PATH = '/auth'

/**
 * State and actions for the two-step auth dialog (email, then password).
 * The URL is the single source of truth for the mode, so a refresh or a shared
 * link always opens the same tab.
 *
 * The dialog owns where a user goes after signing in. While a sign-in is completing it
 * stays mounted (even though the user is already authenticated) until it has navigated,
 * so nothing else can redirect first; a visitor who arrives already signed in is sent
 * to the dashboard (`signedInAlready`).
 */
export function useAuthFlow() {
  const nav = useNavigate()
  const { pathname } = useLocation()
  const { login, signup, isAuthenticated, loading } = useAuth()

  const mode: AuthMode = pathname === SIGNUP_PATH ? 'signup' : 'login'
  const [step, setStep] = useState<AuthStep>('email')
  const [formError, setFormError] = useState('')
  const [completing, setCompleting] = useState(false)

  const resolver = useMemo(() => zodResolver(schemaFor(mode)), [mode])
  const form = useForm<AuthValues>({ resolver, mode: 'onBlur' })

  const switchMode = (next: AuthMode) => {
    if (next === mode) return
    setStep('email')
    setFormError('')
    form.reset()
    nav(next === 'signup' ? SIGNUP_PATH : LOGIN_PATH, { replace: true })
  }

  const continueWithEmail = async () => {
    setFormError('')
    if (await form.trigger('email')) setStep('password')
  }

  const backToEmail = () => {
    setFormError('')
    setStep('email')
  }

  const close = useCallback(() => nav('/'), [nav])

  const submit = form.handleSubmit(async ({ email, password, name }) => {
    setFormError('')
    setCompleting(true)
    try {
      if (mode === 'signup') await signup(email, password, name || undefined)
      else await login(email, password)
      // Replace, so Back does not return to a sign-in form for a signed-in user.
      nav(await resolvePostAuthPath(mode), { replace: true })
    } catch (err) {
      setCompleting(false)
      setFormError(err instanceof Error ? err.message : 'Could not sign in. Please try again.')
    }
  })

  return {
    /** Arrived (or became) signed in without this dialog completing a sign-in: leave for the dashboard. */
    signedInAlready: !loading && isAuthenticated && !completing,
    mode,
    step,
    form,
    formError,
    switchMode,
    continueWithEmail,
    backToEmail,
    submit,
    close,
  }
}

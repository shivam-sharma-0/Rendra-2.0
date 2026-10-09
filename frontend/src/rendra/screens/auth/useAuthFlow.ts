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
 */
export function useAuthFlow() {
  const nav = useNavigate()
  const { pathname } = useLocation()
  const { login, signup } = useAuth()

  const mode: AuthMode = pathname === SIGNUP_PATH ? 'signup' : 'login'
  const [step, setStep] = useState<AuthStep>('email')
  const [formError, setFormError] = useState('')

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
    try {
      if (mode === 'signup') await signup(email, password, name || undefined)
      else await login(email, password)
      nav(await resolvePostAuthPath(mode))
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not sign in. Please try again.')
    }
  })

  return {
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

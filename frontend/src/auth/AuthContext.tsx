import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi, clearLegacyStorage, session, type AuthUser } from './api'

export type { AuthUser } from './api'

type AuthContextValue = {
  user: AuthUser | null
  isAuthenticated: boolean
  /** True while the initial silent-refresh is in flight — guards should wait on this. */
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  signup: (email: string, password: string, name?: string) => Promise<void>
  signOut: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

  // On load, try to restore the session from the HttpOnly refresh cookie.
  useEffect(() => {
    let cancelled = false
    clearLegacyStorage()
    authApi
      .refresh()
      .then((res) => {
        if (cancelled) return
        session.set(res)
        setUser(res.user)
      })
      .catch(() => session.clear())
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login(email, password)
    session.set(res)
    setUser(res.user)
  }, [])

  const signup = useCallback(async (email: string, password: string, name?: string) => {
    const res = await authApi.signup(email, password, name)
    session.set(res)
    setUser(res.user)
  }, [])

  const signOut = useCallback(() => {
    void authApi.logout()
    session.clear()
    setUser(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: user !== null, loading, login, signup, signOut }),
    [user, loading, login, signup, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}

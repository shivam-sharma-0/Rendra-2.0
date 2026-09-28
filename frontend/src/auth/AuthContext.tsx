import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  authApi,
  clearLegacyStorage,
  clearLoggedOutMarker,
  markLoggedOut,
  session,
  wasExplicitlyLoggedOut,
  type AuthUser,
} from './api'
import { useQueryClient } from '@tanstack/react-query'

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

// React StrictMode mounts effects twice in development. Share the same bootstrap request so the
// second mount cannot produce a second /refresh call.
let initialRefresh: Promise<Awaited<ReturnType<typeof authApi.refresh>>> | null = null

function refreshInitialSession() {
  if (!initialRefresh) {
    initialRefresh = authApi.refresh().finally(() => {
      initialRefresh = null
    })
  }
  return initialRefresh
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

  // On load, try to restore the session from the HttpOnly refresh cookie.
  useEffect(() => {
    let cancelled = false
    clearLegacyStorage()
    // After an explicit logout, the refresh cookie has been cleared. Do not call the backend
    // again on a reload of the public app. OAuth callback is the one intentional exception.
    const isOAuthCallback = window.location.pathname === '/oauth/callback'
    if (wasExplicitlyLoggedOut() && !isOAuthCallback) {
      setLoading(false)
      return () => {
        cancelled = true
      }
    }
    refreshInitialSession()
      .then((res) => {
        if (cancelled) return
        clearLoggedOutMarker()
        session.set(res)
        setUser(res.user)
      })
      .catch(() => {
        markLoggedOut()
        session.clear()
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login(email, password)
    clearLoggedOutMarker()
    session.set(res)
    setUser(res.user)
  }, [])

  const signup = useCallback(async (email: string, password: string, name?: string) => {
    const res = await authApi.signup(email, password, name)
    clearLoggedOutMarker()
    session.set(res)
    setUser(res.user)
  }, [])

  const signOut = useCallback(() => {
    markLoggedOut()
    void queryClient.cancelQueries()
    queryClient.clear()
    void authApi.logout()
    session.clear()
    setUser(null)
  }, [queryClient])

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

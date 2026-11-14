import { useSyncExternalStore } from 'react'
import type { AuthSession, Credentials } from './types'

export type Session = Readonly<AuthSession & { email: string; role: 'admin' | 'member' }>
const storageKey = {{storageKey}}
const listeners = new Set<() => void>()

function decode(value: unknown): Session | null {
  if (!value || typeof value !== 'object') return null
  const record = value as Record<string, unknown>
  if (typeof record.token !== 'string' || !record.token || record.token.length > 8192
    || typeof record.userId !== 'string' || !record.userId || record.userId.length > 128
    || typeof record.email !== 'string' || record.email.length > 320) return null
  return Object.freeze({ token: record.token, userId: record.userId, email: record.email,
    role: record.role === 'admin' ? 'admin' as const : 'member' as const })
}

function readSession(): Session | null {
  try { return decode(JSON.parse(window.localStorage.getItem(storageKey) ?? 'null')) }
  catch { return null }
}

let current = readSession()
function publish(session: Session | null, persist = true): void {
  current = session
  if (persist) {
    try {
      if (session) window.localStorage.setItem(storageKey, JSON.stringify(session))
      else window.localStorage.removeItem(storageKey)
    } catch { /* Restricted browser storage keeps a session in memory. */ }
  }
  listeners.forEach(notify => notify())
}

if (typeof window !== 'undefined') window.addEventListener('storage', event => {
  if (event.key !== storageKey && event.key !== null) return
  let session: Session | null = null
  try { session = decode(JSON.parse(event.newValue ?? 'null')) } catch { /* Ignore malformed stored data. */ }
  publish(session, false)
})

async function authenticate(mode: 'login' | 'signup', credentials: Credentials, signal?: AbortSignal): Promise<Session> {
  const email = credentials.email.trim().toLowerCase()
  const response = await fetch(`/api/auth/${mode}`, {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: credentials.password }),
  })
  if (!response.ok) throw new Error(`Authentication failed (${response.status})`)
  const body: unknown = await response.json()
  const session = decode(body && typeof body === 'object' ? { ...body, email } : null)
  if (!session) throw new Error('Authentication returned an invalid session')
  publish(session)
  return session
}

async function refreshRole(): Promise<void> {
  const session = current
  if (!session) return
  try {
    const response = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${session.token}` } })
    if (response.status === 401) { publish(null); return }
    if (!response.ok) return
    const body: unknown = await response.json()
    const role = body && typeof body === 'object' && (body as Record<string, unknown>).role === 'admin' ? 'admin' : 'member'
    if (current === session && role !== session.role) publish(Object.freeze({ ...session, role }))
  } catch {
    return
  }
}
if (typeof window !== 'undefined') void refreshRole()

export const auth = {
  getSession: (): Session | null => current,
  isAdmin: (): boolean => current?.role === 'admin',
  getToken: (): string | undefined => current?.token,
  signIn: (credentials: Credentials, signal?: AbortSignal) => authenticate('login', credentials, signal),
  signUp: (credentials: Credentials, signal?: AbortSignal) => authenticate('signup', credentials, signal),
  signOut: () => publish(null),
}

function subscribe(notify: () => void): () => void {
  listeners.add(notify)
  return () => { listeners.delete(notify) }
}

export function useSession(): Session | null {
  return useSyncExternalStore(subscribe, auth.getSession, () => null)
}

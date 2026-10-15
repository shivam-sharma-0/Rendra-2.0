/**
 * Auth API client + in-memory session.
 *
 * Security model: the refresh token lives only in an HttpOnly cookie the browser manages —
 * it is never visible to JS. The short-lived access token is kept in memory (this module),
 * never in localStorage, so a page reload silently re-establishes the session via /refresh.
 *
 * In dev, requests go to relative `/api/...` and Vite proxies them to the backend. The OAuth
 * login is a full-page navigation and needs the backend origin directly — hence BACKEND_ORIGIN.
 */

export type AuthUser = { id: string; email: string; name: string | null; role: string }
export type SessionResponse = { accessToken: string; user: AuthUser }

const API_BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? ''
export const BACKEND_ORIGIN = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:8080'
const LOGGED_OUT_KEY = 'cp_logged_out'

/** Fail a request that hangs rather than leaving the UI stuck forever. */
const DEFAULT_TIMEOUT_MS = 30_000

/** A short correlation id the backend echoes in logs (X-Request-Id) — pairs client + server traces. */
function newRequestId(): string {
  return (crypto.randomUUID?.() ?? Math.random().toString(36).slice(2)).replace(/-/g, '').slice(0, 24)
}

/**
 * Merge a caller's abort signal (e.g. React Query cancellation) with a timeout signal, so requests
 * abort on whichever fires first. Falls back gracefully where AbortSignal.any is unavailable.
 */
function withRequestDefaults(init?: RequestInit): RequestInit {
  const headers = new Headers(init?.headers)
  if (!headers.has('X-Request-Id')) headers.set('X-Request-Id', newRequestId())
  const timeout = AbortSignal.timeout(DEFAULT_TIMEOUT_MS)
  const signal =
    init?.signal && typeof AbortSignal.any === 'function'
      ? AbortSignal.any([init.signal, timeout])
      : (init?.signal ?? timeout)
  return { ...init, headers, signal }
}

/** Full-page redirect target that kicks off Google login on the backend. */
export const googleLoginUrl = () => `${BACKEND_ORIGIN}/oauth2/authorization/google`

// ── In-memory session (no tokens in localStorage) ──────────────────────────────
let accessToken: string | null = null
let currentUser: AuthUser | null = null
let authEpoch = 0
let refreshInFlight: Promise<SessionResponse> | null = null

export const session = {
  access: () => accessToken,
  user: () => currentUser,
  set(res: SessionResponse) {
    if (currentUser?.id !== res.user.id) authEpoch++
    accessToken = res.accessToken
    currentUser = res.user
  },
  clear() {
    authEpoch++
    accessToken = null
    currentUser = null
  },
}

/** Remember an explicit logout for this tab so bootstrap does not probe /refresh again. */
export function markLoggedOut() {
  sessionStorage.setItem(LOGGED_OUT_KEY, '1')
}

/** A successful password/OAuth login clears the logout guard. */
export function clearLoggedOutMarker() {
  sessionStorage.removeItem(LOGGED_OUT_KEY)
}

export function wasExplicitlyLoggedOut(): boolean {
  return sessionStorage.getItem(LOGGED_OUT_KEY) === '1'
}

/** Remove any tokens/PII a previous build (or another tool) may have left in localStorage. */
export function clearLegacyStorage() {
  for (const k of ['cp_access', 'cp_refresh', 'cp_user', 'auth_token', 'refresh_token', 'user',
    'lovable_recent_projects', 'lovable_starred_projects', 'lovable_theme_chosen']) {
    localStorage.removeItem(k)
  }
}

/** Error carrying the backend's human-readable message (ApiError.message). */
export class ApiClientError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
  }
}

async function toError(res: Response): Promise<ApiClientError> {
  let message = res.statusText || 'Request failed'
  try {
    const body = (await res.json()) as { message?: string }
    if (body?.message) message = body.message
  } catch {
    /* non-JSON body — keep the status text */
  }
  return new ApiClientError(res.status, message)
}

async function postPublic<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, withRequestDefaults({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include', // let the browser store the refresh cookie
    body: JSON.stringify(body),
  }))
  if (!res.ok) throw await toError(res)
  return res.json() as Promise<T>
}

export const authApi = {
  login: (email: string, password: string) =>
    postPublic<SessionResponse>('/api/auth/login', { email, password }).then(res => { authEpoch++; return res }),

  signup: (email: string, password: string, name?: string) =>
    postPublic<SessionResponse>('/api/auth/signup', { email, password, name: name || null }).then(res => { authEpoch++; return res }),

  /** Exchange the refresh cookie for a fresh access token (no body). */
  async refresh(): Promise<SessionResponse> {
    if (!refreshInFlight) {
      const exchange = async () => {
        const res = await fetch(`${API_BASE}/api/auth/refresh`, withRequestDefaults({ method: 'POST', credentials: 'include' }))
        if (!res.ok) throw await toError(res)
        return res.json() as Promise<SessionResponse>
      }
      const operation = typeof navigator !== 'undefined' && navigator.locks
        ? navigator.locks.request('codepal-auth-refresh', { signal: AbortSignal.timeout(DEFAULT_TIMEOUT_MS) }, exchange)
        : exchange()
      refreshInFlight = operation.finally(() => { refreshInFlight = null })
    }
    return refreshInFlight
  },

  /** Clear the refresh cookie server-side. */
  async logout(): Promise<void> {
    try {
      await fetch(`${API_BASE}/api/auth/logout`,
        withRequestDefaults({ method: 'POST', credentials: 'include' }))
    } catch {
      /* best-effort */
    }
  },
}

/** Refresh an expiring socket credential without restoring a logged-out or switched account. */
export async function realtimeAccess(): Promise<string | null> {
  const token = accessToken
  const epoch = authEpoch
  if (!token || wasExplicitlyLoggedOut()) return null
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const expiry = Number(JSON.parse(atob(encoded)).exp)
    if (Number.isFinite(expiry) && expiry * 1000 > Date.now() + 30_000) return token
  } catch { /* An unreadable token must be exchanged, not retried forever. */ }
  try {
    const refreshed = await authApi.refresh()
    if (authEpoch !== epoch || wasExplicitlyLoggedOut()) return null
    if (currentUser?.id !== refreshed.user.id) { session.clear(); return null }
    session.set(refreshed)
    return accessToken
  } catch {
    if (authEpoch === epoch) session.clear()
    return null
  }
}

/**
 * Authenticated fetch: attaches the in-memory access token and, on a 401, silently refreshes it
 * via the cookie once before retrying. Always sends credentials so the cookie rides along.
 */
async function authFetch(path: string, init?: RequestInit, allowRetry = true): Promise<Response> {
  const withDefaults = withRequestDefaults(init)
  const headers = withDefaults.headers as Headers
  const tokenAtStart = accessToken
  const epochAtStart = authEpoch
  if (tokenAtStart) headers.set('Authorization', `Bearer ${tokenAtStart}`)

  const res = await fetch(`${API_BASE}${path}`, { ...withDefaults, credentials: 'include' })
  // Do not turn a stale request from before logout into a refresh request. A request made
  // after logout has no access token either, so it must also fail without probing /refresh.
  if (res.status !== 401 || !allowRetry || !tokenAtStart || authEpoch !== epochAtStart || !accessToken) return res
  if (accessToken !== tokenAtStart) return authFetch(path, init, false)

  try {
    const refreshed = await authApi.refresh()
    // Logout may have happened while the refresh request was in flight. Never let that stale
    // response restore an authenticated session after the user explicitly signed out.
    if (authEpoch !== epochAtStart || wasExplicitlyLoggedOut()) return res
    if (currentUser?.id !== refreshed.user.id) { session.clear(); return res }
    session.set(refreshed)
  } catch {
    if (authEpoch === epochAtStart) session.clear()
    return res
  }
  return authFetch(path, init, false)
}

/** GET a JSON resource behind auth. Pass a signal (e.g. from React Query) to allow cancellation. */
export async function apiGet<T>(path: string, opts?: { signal?: AbortSignal }): Promise<T> {
  const res = await authFetch(path, { signal: opts?.signal })
  if (!res.ok) throw await toError(res)
  return res.json() as Promise<T>
}

/** Private downloads use the same refresh/session handling as JSON requests. */
export async function apiBlob(path: string, opts?: { signal?: AbortSignal }): Promise<Blob> {
  const res = await authFetch(path, { signal: opts?.signal })
  if (!res.ok) throw await toError(res)
  return res.blob()
}

/**
 * Send a JSON body (POST/PATCH/PUT) behind auth. Returns undefined for 204 responses.
 * An {@link opts.idempotencyKey} makes a create/mutation safe to retry (backend de-dupes on it).
 */
export async function apiSend<T>(
  method: string,
  path: string,
  body?: unknown,
  opts?: { signal?: AbortSignal; idempotencyKey?: string },
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (opts?.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey
  const res = await authFetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: opts?.signal,
  })
  if (!res.ok) throw await toError(res)
  return (res.status === 204 ? undefined : await res.json()) as T
}

/** Upload multipart files behind auth without manually setting Content-Type (the browser adds the boundary). */
export async function apiUpload<T>(path: string, files: File[]): Promise<T> {
  const body = new FormData()
  files.forEach((file) => body.append('files', file, file.name))
  const res = await authFetch(path, { method: 'POST', body })
  if (!res.ok) throw await toError(res)
  return res.json() as Promise<T>
}

/** DELETE behind auth. */
export async function apiDelete(path: string): Promise<void> {
  const res = await authFetch(path, { method: 'DELETE' })
  if (!res.ok) throw await toError(res)
}

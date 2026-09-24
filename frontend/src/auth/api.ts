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

/** Full-page redirect target that kicks off Google login on the backend. */
export const googleLoginUrl = () => `${BACKEND_ORIGIN}/oauth2/authorization/google`

// ── In-memory session (no tokens in localStorage) ──────────────────────────────
let accessToken: string | null = null
let currentUser: AuthUser | null = null

export const session = {
  access: () => accessToken,
  user: () => currentUser,
  set(res: SessionResponse) {
    accessToken = res.accessToken
    currentUser = res.user
  },
  clear() {
    accessToken = null
    currentUser = null
  },
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
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include', // let the browser store the refresh cookie
    body: JSON.stringify(body),
  })
  if (!res.ok) throw await toError(res)
  return res.json() as Promise<T>
}

export const authApi = {
  login: (email: string, password: string) =>
    postPublic<SessionResponse>('/api/auth/login', { email, password }),

  signup: (email: string, password: string, name?: string) =>
    postPublic<SessionResponse>('/api/auth/signup', { email, password, name: name || null }),

  /** Exchange the refresh cookie for a fresh access token (no body). */
  async refresh(): Promise<SessionResponse> {
    const res = await fetch(`${API_BASE}/api/auth/refresh`, { method: 'POST', credentials: 'include' })
    if (!res.ok) throw await toError(res)
    return res.json() as Promise<SessionResponse>
  },

  /** Clear the refresh cookie server-side. */
  async logout(): Promise<void> {
    try {
      await fetch(`${API_BASE}/api/auth/logout`, { method: 'POST', credentials: 'include' })
    } catch {
      /* best-effort */
    }
  },
}

/**
 * Authenticated fetch: attaches the in-memory access token and, on a 401, silently refreshes it
 * via the cookie once before retrying. Always sends credentials so the cookie rides along.
 */
async function authFetch(path: string, init?: RequestInit, allowRetry = true): Promise<Response> {
  const headers = new Headers(init?.headers)
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)

  const res = await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: 'include' })
  if (res.status !== 401 || !allowRetry) return res

  try {
    session.set(await authApi.refresh())
  } catch {
    session.clear()
    return res
  }
  return authFetch(path, init, false)
}

/** GET a JSON resource behind auth. */
export async function apiGet<T>(path: string): Promise<T> {
  const res = await authFetch(path)
  if (!res.ok) throw await toError(res)
  return res.json() as Promise<T>
}

/** Send a JSON body (POST/PATCH/PUT) behind auth. Returns undefined for 204 responses. */
export async function apiSend<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await authFetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw await toError(res)
  return (res.status === 204 ? undefined : await res.json()) as T
}

/** DELETE behind auth. */
export async function apiDelete(path: string): Promise<void> {
  const res = await authFetch(path, { method: 'DELETE' })
  if (!res.ok) throw await toError(res)
}

function createRequest(session?: { getToken: () => string | undefined; signOut: () => void }) {
  return async function request<T>(path: string, method: string, body: unknown, token?: string): Promise<T> {
    const accessToken = token ?? session?.getToken()
    const response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    if (response.status === 401) session?.signOut()
    if (!response.ok) throw new Error(`API request failed (${response.status})`)
    return (response.status === 204 ? undefined : await response.json()) as T
  }
}

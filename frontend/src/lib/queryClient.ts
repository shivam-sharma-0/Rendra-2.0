import { QueryClient } from '@tanstack/react-query'
import { ApiClientError } from '../auth/api'

/**
 * Single app-wide React Query client. Sensible enterprise defaults:
 *  - a short staleTime so lists feel live without hammering the API on every mount,
 *  - retries only for genuinely transient failures (never for 4xx — a 400/401/403/404 won't
 *    fix itself on retry), capped with backoff,
 *  - no refetch-on-focus for a builder tool (it would interrupt long-running work).
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        if (error instanceof ApiClientError && error.status >= 400 && error.status < 500) return false
        return failureCount < 2
      },
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8_000),
    },
    mutations: {
      retry: false,
    },
  },
})

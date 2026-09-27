import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { type Workspace } from '../api/projects'
import { useWorkspaces } from '../api/queries'
import { useAuth } from '../auth/AuthContext'

const STORAGE_KEY = 'cp_ws_selected'

type WorkspaceContextValue = {
  workspaces: Workspace[]
  selected: Workspace | null
  selectedId: string | null
  setSelectedId: (id: string) => void
  reload: () => Promise<unknown>
  loading: boolean
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null)

/**
 * App-wide "current workspace" state. The workspace list is sourced from React Query (cached and
 * shared across the app — no duplicate fetches), while the *selection* is local UI state persisted
 * to localStorage. The selection falls back to the personal workspace when nothing valid is stored.
 */
export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth()
  const { data, isLoading, refetch } = useWorkspaces(isAuthenticated)
  const workspaces = useMemo(() => (isAuthenticated ? (data ?? []) : []), [isAuthenticated, data])

  const [selectedId, setSelectedIdState] = useState<string | null>(() => localStorage.getItem(STORAGE_KEY))

  const setSelectedId = useCallback((id: string) => {
    setSelectedIdState(id)
    localStorage.setItem(STORAGE_KEY, id)
  }, [])

  // Reconcile the stored selection against the loaded list: keep it if still valid, else fall back.
  useEffect(() => {
    if (!workspaces.length) return
    setSelectedIdState((current) => {
      if (current && workspaces.some((w) => w.id === current)) return current
      const fallback = workspaces.find((w) => w.personal) ?? workspaces[0]
      if (fallback) localStorage.setItem(STORAGE_KEY, fallback.id)
      return fallback?.id ?? null
    })
  }, [workspaces])

  const selected = useMemo(() => workspaces.find((w) => w.id === selectedId) ?? null, [workspaces, selectedId])
  const value = useMemo(
    () => ({ workspaces, selected, selectedId, setSelectedId, reload: refetch, loading: isAuthenticated && isLoading }),
    [workspaces, selected, selectedId, setSelectedId, refetch, isAuthenticated, isLoading],
  )
  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>
}

export function useWorkspace() {
  const ctx = useContext(WorkspaceContext)
  if (!ctx) throw new Error('useWorkspace must be used within WorkspaceProvider')
  return ctx
}

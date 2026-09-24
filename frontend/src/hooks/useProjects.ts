import { useEffect, useState } from 'react'
import { projectApi, workspaceApi, type Project, type ProjectType } from '../api/projects'

/**
 * Load the personal workspace's projects, optionally filtered by type. Returns null while
 * loading. The stable key derived from {@code types} keeps the fetch from re-running per render.
 */
export function useProjects(types?: ProjectType[]) {
  const [projects, setProjects] = useState<Project[] | null>(null)
  const key = types?.join(',') ?? 'all'

  useEffect(() => {
    let cancelled = false
    setProjects(null)
    ;(async () => {
      try {
        const workspaces = await workspaceApi.list()
        const ws = workspaces.find((w) => w.personal) ?? workspaces[0]
        if (cancelled || !ws) return
        const page = await projectApi.list(ws.id, { types, size: 50 })
        if (!cancelled) setProjects(page.content)
      } catch {
        if (!cancelled) setProjects([])
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  return projects
}

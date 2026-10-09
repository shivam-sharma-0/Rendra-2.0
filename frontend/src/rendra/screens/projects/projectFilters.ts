import type { Project } from '../../../api/projects'

export type ProjectTab = 'all' | 'mine' | 'shared' | 'starred'
export type StatusFilter = 'any' | 'LIVE' | 'PENDING' | 'NONE'
export type ProjectSort = 'recent' | 'name'
export type ProjectLayout = 'list' | 'grid'

export const STATUS_OPTIONS: readonly { id: StatusFilter; label: string }[] = [
  { id: 'any', label: 'Any status' },
  { id: 'LIVE', label: 'Live' },
  { id: 'PENDING', label: 'Building' },
  { id: 'NONE', label: 'Draft' },
]

type Filters = { tab: ProjectTab; status: StatusFilter; sort: ProjectSort; starred: ReadonlySet<string> }

/**
 * Projects shown for the current tab, status and sort. "Created by me" shows everything because
 * projects carry no owner yet, and "Shared with me" is empty until project sharing exists.
 */
export function filterProjects(all: readonly Project[], { tab, status, sort, starred }: Filters): Project[] {
  let list: Project[] = tab === 'shared' ? [] : tab === 'starred' ? all.filter((p) => starred.has(p.id)) : [...all]
  if (status !== 'any') list = list.filter((p) => p.deploymentStatus === status)
  if (sort === 'name') list = [...list].sort((a, b) => a.name.localeCompare(b.name))
  return list
}

/** Tab counts, matching what `filterProjects` would show with no status filter. */
export function tabCounts(all: readonly Project[], starred: ReadonlySet<string>): Record<ProjectTab, number> {
  return { all: all.length, mine: all.length, shared: 0, starred: all.filter((p) => starred.has(p.id)).length }
}

/** "App", "Plan" or "Conversation", by project type. */
export function kindOf(project: Project): string {
  return project.type === 'CHAT' ? 'Conversation' : project.type === 'PLAN' ? 'Plan' : 'App'
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  agentApi, databaseApi, filesApi, insightsApi, memberApi, messageApi, notificationApi, projectApi, projectMemberApi, startProject, workspaceApi,
  type ProjectRole, type ProjectType, type WorkspaceRole,
} from './projects'
import { newRequestId } from '../lib/ids'

/**
 * Central React Query layer: one place for query keys and typed hooks so every screen shares the
 * same cache, deduping, and invalidation. Screens never call fetch/useEffect directly — they use
 * these hooks, which gives consistent loading/error handling and eliminates duplicate requests.
 */

export const qk = {
  workspaces: ['workspaces'] as const,
  workspace: (id: string) => ['workspaces', id] as const,
  members: (workspaceId: string) => ['workspaces', workspaceId, 'members'] as const,
  projects: (workspaceId: string, types?: ProjectType[]) =>
    ['workspaces', workspaceId, 'projects', { types: types ?? null }] as const,
  project: (id: string) => ['projects', id] as const,
  projectMembers: (projectId: string) => ['projects', projectId, 'members'] as const,
  messages: (projectId: string) => ['projects', projectId, 'messages'] as const,
  agentRuns: (projectId: string) => ['projects', projectId, 'agent-runs'] as const,
  fileTree: (projectId: string) => ['projects', projectId, 'files'] as const,
  fileContent: (projectId: string, path: string) => ['projects', projectId, 'files', path] as const,
  notifications: ['notifications'] as const,
  unreadCount: ['notifications', 'unread-count'] as const,
  analytics: (projectId: string) => ['projects', projectId, 'analytics'] as const,
  securityScan: (projectId: string) => ['projects', projectId, 'security-scan'] as const,
  database: (projectId: string) => ['projects', projectId, 'database'] as const,
}

// ── Queries ────────────────────────────────────────────────────────────────────

export function useWorkspaces(enabled = true) {
  return useQuery({ queryKey: qk.workspaces, queryFn: ({ signal }) => workspaceApi.list({ signal }), enabled })
}

export function useMembers(workspaceId: string | null | undefined) {
  return useQuery({
    queryKey: qk.members(workspaceId ?? ''),
    queryFn: ({ signal }) => memberApi.list(workspaceId!, { signal }),
    enabled: !!workspaceId,
  })
}

export function useProjects(
  workspaceId: string | null | undefined,
  opts: { types?: ProjectType[]; size?: number } = {},
) {
  return useQuery({
    queryKey: qk.projects(workspaceId ?? '', opts.types),
    queryFn: ({ signal }) => projectApi.list(workspaceId!, { ...opts, signal }),
    enabled: !!workspaceId,
  })
}

export function useProject(id: string | null | undefined) {
  return useQuery({
    queryKey: qk.project(id ?? ''),
    queryFn: ({ signal }) => projectApi.get(id!, { signal }),
    enabled: !!id,
  })
}

export function useAgentRuns(projectId: string | null | undefined) {
  return useQuery({
    queryKey: qk.agentRuns(projectId ?? ''),
    queryFn: ({ signal }) => agentApi.list(projectId!, { signal }),
    enabled: !!projectId,
  })
}

export function useFileTree(projectId: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: qk.fileTree(projectId ?? ''),
    queryFn: ({ signal }) => filesApi.tree(projectId!, { signal }),
    enabled: !!projectId && enabled,
  })
}

export function useFileContent(projectId: string | null | undefined, path: string | null) {
  return useQuery({
    queryKey: qk.fileContent(projectId ?? '', path ?? ''),
    queryFn: ({ signal }) => filesApi.content(projectId!, path!, { signal }),
    enabled: !!projectId && !!path,
  })
}

/** Poll the active isolated change set so the code panel can show edits before commit. */
export function useStagedFiles(projectId: string | null | undefined, runId: string | null | undefined, active = true) {
  return useQuery({
    queryKey: ['projects', projectId ?? '', 'agent-runs', runId ?? '', 'staged-files'] as const,
    queryFn: ({ signal }) => agentApi.stagedFiles(projectId!, runId!, { signal }),
    enabled: !!projectId && !!runId && active,
    refetchInterval: active ? 1_000 : false,
  })
}

export function useNotifications(enabled = true) {
  return useQuery({ queryKey: qk.notifications, queryFn: ({ signal }) => notificationApi.list({ signal }), enabled })
}

export function useUnreadCount(enabled = true) {
  return useQuery({
    queryKey: qk.unreadCount,
    queryFn: ({ signal }) => notificationApi.unreadCount({ signal }).then((r) => r.count),
    enabled,
    refetchInterval: 60_000, // light polling so the bell stays roughly current
  })
}

export function useMarkAllNotificationsRead() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: qk.notifications })
      client.invalidateQueries({ queryKey: qk.unreadCount })
    },
  })
}

export function useAnalytics(projectId: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: qk.analytics(projectId ?? ''),
    queryFn: ({ signal }) => insightsApi.analytics(projectId!, { signal }),
    enabled: !!projectId && enabled,
  })
}

export function useSecurityScan(projectId: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: qk.securityScan(projectId ?? ''),
    queryFn: ({ signal }) => insightsApi.securityScan(projectId!, { signal }),
    enabled: !!projectId && enabled,
    staleTime: 5 * 60_000, // a scan is relatively expensive; don't refetch aggressively
  })
}

export function useDatabase(projectId: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: qk.database(projectId ?? ''),
    queryFn: ({ signal }) => databaseApi.get(projectId!, { signal }),
    enabled: !!projectId && enabled,
  })
}

export function useMessages(projectId: string | null | undefined) {
  return useQuery({
    queryKey: qk.messages(projectId ?? ''),
    queryFn: ({ signal }) => messageApi.list(projectId!, 0, 50, { signal }),
    enabled: !!projectId,
  })
}

// ── Mutations ────────────────────────────────────────────────────────────────────

/** Start a new project from a prompt. Carries an idempotency key so a retry can't double-create. */
export function useStartProject() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: { prompt: string | null; mode?: ProjectType; workspaceId?: string }) =>
      startProject(vars.prompt, vars.mode ?? 'BUILD', vars.workspaceId, { idempotencyKey: newRequestId() }),
    onSuccess: (project) => {
      client.invalidateQueries({ queryKey: ['workspaces', project.workspaceId, 'projects'] })
    },
  })
}

export function useCreateWorkspace() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => workspaceApi.create(name),
    onSuccess: () => client.invalidateQueries({ queryKey: qk.workspaces }),
  })
}

export function useProjectMembers(projectId: string | null | undefined) {
  return useQuery({
    queryKey: qk.projectMembers(projectId ?? ''),
    queryFn: ({ signal }) => projectMemberApi.list(projectId!, { signal }),
    enabled: !!projectId,
  })
}

export function useShareProject(projectId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: { email: string; role: ProjectRole }) => projectMemberApi.share(projectId, vars.email, vars.role),
    onSuccess: () => client.invalidateQueries({ queryKey: qk.projectMembers(projectId) }),
  })
}

export function useUpdateProjectMemberRole(projectId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: { memberId: string; role: ProjectRole }) => projectMemberApi.updateRole(projectId, vars.memberId, vars.role),
    onSuccess: () => client.invalidateQueries({ queryKey: qk.projectMembers(projectId) }),
  })
}

export function useRemoveProjectMember(projectId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (memberId: string) => projectMemberApi.remove(projectId, memberId),
    onSuccess: () => client.invalidateQueries({ queryKey: qk.projectMembers(projectId) }),
  })
}

export function useDeleteProject(workspaceId: string | null | undefined) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (projectId: string) => projectApi.remove(projectId),
    onSuccess: () => {
      if (workspaceId) client.invalidateQueries({ queryKey: ['workspaces', workspaceId, 'projects'] })
    },
  })
}

export function useInviteMember(workspaceId: string | null | undefined) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (email: string) => memberApi.invite(workspaceId!, email),
    onSuccess: () => {
      if (workspaceId) client.invalidateQueries({ queryKey: qk.members(workspaceId) })
    },
  })
}

export function useUpdateMemberRole(workspaceId: string | null | undefined) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (vars: { memberId: string; role: WorkspaceRole }) => memberApi.updateRole(workspaceId!, vars.memberId, vars.role),
    onSuccess: () => {
      if (workspaceId) client.invalidateQueries({ queryKey: qk.members(workspaceId) })
    },
  })
}

export function useRemoveMember(workspaceId: string | null | undefined) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (memberId: string) => memberApi.remove(workspaceId!, memberId),
    onSuccess: () => {
      if (workspaceId) client.invalidateQueries({ queryKey: qk.members(workspaceId) })
    },
  })
}

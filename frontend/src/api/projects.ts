/** Typed client for the workspace / project / message endpoints. */
import { apiDelete, apiGet, apiSend } from '../auth/api'
import { newAiMessageId, newUserMessageId } from '../lib/ids'

export type ProjectType = 'BUILD' | 'CHAT' | 'PLAN'
export type DeploymentStatus = 'NONE' | 'PENDING' | 'LIVE' | 'FAILED'
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'
export type MessageStatus = 'PENDING' | 'STREAMING' | 'COMPLETE' | 'FAILED'
export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export type Workspace = {
  id: string
  name: string
  personal: boolean
  role: WorkspaceRole
  createdAt: string
}

export type Project = {
  id: string
  workspaceId: string
  name: string
  initialPrompt: string | null
  type: ProjectType
  isPublic: boolean
  publishedUrl: string | null
  deploymentStatus: DeploymentStatus
  lastActivityAt: string
  createdAt: string
  updatedAt: string
}

export type Message = {
  id: string
  projectId: string
  role: MessageRole
  content: string
  status: MessageStatus
  mode: ProjectType | null
  replyToId: string | null
  createdAt: string
  updatedAt: string
}

/** Spring Data `Page` — only the fields we use. */
export type Page<T> = {
  content: T[]
  number: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export type Member = {
  id: string
  userPublicId: string | null
  email: string | null
  name: string | null
  role: WorkspaceRole
  joinedAt: string
}

export const workspaceApi = {
  list: () => apiGet<Workspace[]>('/api/workspaces'),
  get: (id: string) => apiGet<Workspace>(`/api/workspaces/${id}`),
  create: (name: string) => apiSend<Workspace>('POST', '/api/workspaces', { name }),
  rename: (id: string, name: string) => apiSend<Workspace>('PATCH', `/api/workspaces/${id}`, { name }),
  remove: (id: string) => apiDelete(`/api/workspaces/${id}`),
  leave: (id: string) => apiSend<void>('POST', `/api/workspaces/${id}/leave`),
  transfer: (id: string, memberId: string) => apiSend<void>('POST', `/api/workspaces/${id}/transfer`, { memberId }),
}

export const memberApi = {
  list: (workspaceId: string) => apiGet<Member[]>(`/api/workspaces/${workspaceId}/members`),
  invite: (workspaceId: string, email: string) =>
    apiSend<Member>('POST', `/api/workspaces/${workspaceId}/members`, { email }),
  updateRole: (workspaceId: string, memberId: string, role: WorkspaceRole) =>
    apiSend<Member>('PATCH', `/api/workspaces/${workspaceId}/members/${memberId}`, { role }),
  remove: (workspaceId: string, memberId: string) =>
    apiDelete(`/api/workspaces/${workspaceId}/members/${memberId}`),
}

/** CHAT-only for the "Chats" view; BUILD + PLAN for the "Projects" view. */
export const CHAT_TYPES: ProjectType[] = ['CHAT']
export const PROJECT_TYPES: ProjectType[] = ['BUILD', 'PLAN']

export const projectApi = {
  list: (
    workspaceId: string,
    opts: { types?: ProjectType[]; page?: number; size?: number } = {},
  ) => {
    const params = new URLSearchParams({ page: String(opts.page ?? 0), size: String(opts.size ?? 20) })
    for (const t of opts.types ?? []) params.append('type', t)
    return apiGet<Page<Project>>(`/api/workspaces/${workspaceId}/projects?${params}`)
  },

  create: (
    workspaceId: string,
    body: { name?: string; type?: ProjectType; initialMessage?: CreateMessageBody },
  ) => apiSend<Project>('POST', `/api/workspaces/${workspaceId}/projects`, body),

  get: (id: string) => apiGet<Project>(`/api/projects/${id}`),

  remove: (id: string) => apiDelete(`/api/projects/${id}`),
}

export type CreateMessageBody = {
  id: string
  message: string
  mode?: ProjectType
  aiMessageId?: string
}

export const messageApi = {
  list: (projectId: string, page = 0, size = 50) =>
    apiGet<Page<Message>>(`/api/projects/${projectId}/messages?page=${page}&size=${size}`),

  post: (projectId: string, message: string, mode?: ProjectType) =>
    apiSend<Message[]>('POST', `/api/projects/${projectId}/messages`, {
      id: newUserMessageId(),
      message,
      mode,
      aiMessageId: newAiMessageId(),
    } satisfies CreateMessageBody),
}

/**
 * Kick off a build from a raw prompt: resolve the user's personal workspace, then create a
 * project seeded with the first message (+ a reserved assistant reply). Returns the new project.
 */
export async function startProject(prompt: string, mode: ProjectType = 'BUILD'): Promise<Project> {
  const workspaces = await workspaceApi.list()
  const workspace = workspaces.find((w) => w.personal) ?? workspaces[0]
  if (!workspace) throw new Error('No workspace available')

  return projectApi.create(workspace.id, {
    type: mode,
    initialMessage: {
      id: newUserMessageId(),
      message: prompt,
      mode,
      aiMessageId: newAiMessageId(),
    },
  })
}

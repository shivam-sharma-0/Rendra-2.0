import { apiGet, apiSend, apiUpload } from '../auth/api'
import { newAiMessageId, newUserMessageId } from '../lib/ids'

export type ProjectType = 'BUILD' | 'CHAT' | 'PLAN'
export type ProjectStage = 'REQUIREMENTS' | 'DESIGN' | 'BUILDING' | 'REVIEW' | 'DEPLOYING' | 'LIVE' | 'FAILED'
export type RequirementStatus = 'DRAFT' | 'IN_PROGRESS' | 'APPROVAL_REQUIRED' | 'APPROVED'
export type RequirementQuestionType = 'SINGLE_SELECT' | 'MULTI_SELECT' | 'TEXT'
export type DeploymentStatus = 'NONE' | 'PENDING' | 'LIVE' | 'FAILED'
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'
export type MessageStatus = 'PENDING' | 'STREAMING' | 'COMPLETE' | 'FAILED'
export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER'
export type AgentRunStatus = 'QUEUED' | 'RUNNING' | 'AWAITING_INPUT' | 'DONE' | 'FAILED' | 'CANCELLED'
export type AgentStepType = 'THINKING' | 'TOOL_CALL' | 'TOOL_RESULT' | 'ASK_USER' | 'MESSAGE' | 'STATUS'

export type Workspace = { id: string; name: string; personal: boolean; role: WorkspaceRole; createdAt: string }
export type Project = {
  id: string; workspaceId: string; name: string; initialPrompt: string | null; type: ProjectType
  lifecycleStage: ProjectStage
  isPublic: boolean; publishedUrl: string | null; deploymentStatus: DeploymentStatus
  lastActivityAt: string; createdAt: string; updatedAt: string
}
export type MessageFeedback = 'UP' | 'DOWN'
export type Message = {
  id: string; projectId: string; role: MessageRole; content: string; status: MessageStatus
  mode: ProjectType | null; replyToId: string | null; feedback: MessageFeedback | null
  seq: number; createdAt: string; updatedAt: string; attachments?: string[] | null
}
export type AgentRun = {
  id: string; projectId: string; messageId: string; mode: ProjectType; status: AgentRunStatus
  promptTokens: number; completionTokens: number; rollbackAvailable: boolean; conversational: boolean
  createdAt: string; updatedAt: string; respondingAt: string | null
}
export type AgentStep = { seq: number; type: AgentStepType; content: string | null; data: string | null; createdAt: string }
export type StagedFile = { path: string; content: string | null; deleted: boolean }
export type FileNode = { name: string; path: string; dir: boolean; size: number; children: FileNode[] | null }
export type FileContent = { path: string; content: string; truncated: boolean; binary: boolean }

export type Page<T> = {
  content: T[]; number: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean
}
export type Member = { id: string; userPublicId: string | null; email: string | null; name: string | null; role: WorkspaceRole; joinedAt: string }
export type ProjectRole = 'OWNER' | 'EDITOR' | 'VIEWER'
export type ProjectMember = { id: string; userPublicId: string | null; email: string | null; name: string | null; role: ProjectRole; sharedAt: string }
export type NotificationType = 'BUILD_FINISHED' | 'BUILD_FAILED' | 'PROJECT_SHARED' | 'WORKSPACE_INVITE' | 'ROLE_CHANGED'
export type AppNotification = { id: string; type: NotificationType; title: string; body: string | null; projectId: string | null; read: boolean; createdAt: string }

export type ReadOpts = { signal?: AbortSignal }

export const workspaceApi = {
  list: (opts?: ReadOpts) => apiGet<Workspace[]>('/api/workspaces', opts),
  get: (id: string, opts?: ReadOpts) => apiGet<Workspace>(`/api/workspaces/${id}`, opts),
  create: (name: string) => apiSend<Workspace>('POST', '/api/workspaces', { name }),
  rename: (id: string, name: string) => apiSend<Workspace>('PATCH', `/api/workspaces/${id}`, { name }),
  remove: (id: string) => apiSend<void>('DELETE', `/api/workspaces/${id}`),
  leave: (id: string) => apiSend<void>('POST', `/api/workspaces/${id}/leave`),
  transfer: (id: string, memberId: string) => apiSend<void>('POST', `/api/workspaces/${id}/transfer`, { memberId }),
}

export type InvitePreview = { workspaceName: string | null; invitedByName: string | null; role: WorkspaceRole | null; valid: boolean; reason: string | null }
export type WorkspaceInvite = { token: string; workspaceName: string; role: WorkspaceRole; expiresAt: string }

export const inviteApi = {
  create: (workspaceId: string, body?: { email?: string; role?: WorkspaceRole }) =>
    apiSend<WorkspaceInvite>('POST', `/api/workspaces/${workspaceId}/invites`, body ?? {}),
  preview: (token: string, opts?: ReadOpts) => apiGet<InvitePreview>(`/api/invites/${token}`, opts),
  accept: (token: string) => apiSend<void>('POST', `/api/invites/${token}/accept`),
}

export const memberApi = {
  list: (workspaceId: string, opts?: ReadOpts) => apiGet<Member[]>(`/api/workspaces/${workspaceId}/members`, opts),
  invite: (workspaceId: string, email: string) => apiSend<Member>('POST', `/api/workspaces/${workspaceId}/members`, { email }),
  updateRole: (workspaceId: string, memberId: string, role: WorkspaceRole) => apiSend<Member>('PATCH', `/api/workspaces/${workspaceId}/members/${memberId}`, { role }),
  remove: (workspaceId: string, memberId: string) => apiSend<void>('DELETE', `/api/workspaces/${workspaceId}/members/${memberId}`),
}

export const CHAT_TYPES: ProjectType[] = ['CHAT']
export const PROJECT_TYPES: ProjectType[] = ['BUILD', 'PLAN']

export const projectApi = {
  list: (
    workspaceId: string,
    opts: { types?: ProjectType[]; page?: number; size?: number; signal?: AbortSignal } = {},
  ) => {
    const params = new URLSearchParams({ page: String(opts.page ?? 0), size: String(opts.size ?? 20) })
    for (const type of opts.types ?? []) params.append('type', type)
    return apiGet<Page<Project>>(`/api/workspaces/${workspaceId}/projects?${params}`, { signal: opts.signal })
  },
  create: (
    workspaceId: string,
    body: { name?: string; type?: ProjectType; initialMessage?: CreateMessageBody },
    opts?: { idempotencyKey?: string },
  ) => apiSend<Project>('POST', `/api/workspaces/${workspaceId}/projects`, body, opts),
  get: (id: string, opts?: ReadOpts) => apiGet<Project>(`/api/projects/${id}`, opts),
  previewToken: (id: string, opts?: ReadOpts) => apiGet<{ token: string; url: string }>(`/api/projects/${id}/preview-token`, opts),
  uploadAttachments: (id: string, files: File[]) => apiUpload<Attachment[]>(`/api/projects/${id}/attachments`, files),
  remove: (id: string) => apiSend<void>('DELETE', `/api/projects/${id}`),
  publish: (id: string) => apiSend<Project>('POST', `/api/projects/${id}/publish`),
  unpublish: (id: string) => apiSend<Project>('POST', `/api/projects/${id}/unpublish`),
}

export type Attachment = { path: string; originalName: string; size: number; contentType: string | null }

export type RequirementOption = { id: string; label: string; description: string; recommended: boolean }
export type RequirementQuestion = {
  id: string; sequence: number; key: string; prompt: string; type: RequirementQuestionType; required: boolean
  options: RequirementOption[]; recommendedOption: string | null; selectedOption: string | null; otherText: string | null
}
export type RequirementSession = {
  id: string | null; projectId: string; status: RequirementStatus; questions: RequirementQuestion[]
  complete: boolean; approvedAt: string | null
}
export type DatabaseSnapshot = { provider: string; database: string; reachable: boolean; collections: string[]; error: string | null; checkedAt: string }

export const requirementsApi = {
  get: (projectId: string, opts?: ReadOpts) => apiGet<RequirementSession>(`/api/projects/${projectId}/requirements`, opts),
  start: (projectId: string) => apiSend<RequirementSession>('POST', `/api/projects/${projectId}/requirements/start`),
  answer: (projectId: string, questionId: string, body: { selectedOption?: string | null; otherText?: string | null }) =>
    apiSend<RequirementSession>('PUT', `/api/projects/${projectId}/requirements/questions/${questionId}`, body),
  approve: (projectId: string) => apiSend<RequirementSession>('POST', `/api/projects/${projectId}/requirements/approve`),
}

export const databaseApi = {
  get: (projectId: string, opts?: ReadOpts) => apiGet<DatabaseSnapshot>(`/api/projects/${projectId}/database`, opts),
}

export type CreateMessageBody = { id: string; message: string; mode?: ProjectType; aiMessageId?: string; attachments?: string[] }
export const messageApi = {
  list: (projectId: string, page = 0, size = 50, opts?: ReadOpts) =>
    apiGet<Page<Message>>(`/api/projects/${projectId}/messages?page=${page}&size=${size}`, opts),
  // attachments: paths returned by uploadAttachments, so the agent reads them with this turn
  post: (projectId: string, message: string, mode?: ProjectType, attachments?: string[]) => apiSend<Message[]>('POST', `/api/projects/${projectId}/messages`, {
    id: newUserMessageId(), message, mode, aiMessageId: newAiMessageId(),
    ...(attachments?.length ? { attachments } : {}),
  } satisfies CreateMessageBody),
  feedback: (projectId: string, messageId: string, feedback: MessageFeedback | null) =>
    apiSend<Message>('PUT', `/api/projects/${projectId}/messages/${messageId}/feedback`, { feedback }),
}

/** Timeline replay, cancel and rollback operations for the project agent. */
export const agentApi = {
  list: (projectId: string, opts?: ReadOpts) => apiGet<AgentRun[]>(`/api/projects/${projectId}/agent-runs`, opts),
  steps: (projectId: string, runId: string, afterSeq = -1, opts?: ReadOpts) =>
    apiGet<AgentStep[]>(`/api/projects/${projectId}/agent-runs/${runId}/steps?afterSeq=${afterSeq}`, opts),
  stagedFiles: (projectId: string, runId: string, opts?: ReadOpts) => apiGet<StagedFile[]>(`/api/projects/${projectId}/agent-runs/${runId}/staged-files`, opts),
  cancel: (projectId: string, runId: string) => apiSend<void>('POST', `/api/projects/${projectId}/agent-runs/${runId}/cancel`),
  rollback: (projectId: string, runId: string) => apiSend<void>('POST', `/api/projects/${projectId}/agent-runs/${runId}/rollback`),
}

/** Per-project sharing (Share panel). */
export const projectMemberApi = {
  list: (projectId: string, opts?: ReadOpts) => apiGet<ProjectMember[]>(`/api/projects/${projectId}/members`, opts),
  share: (projectId: string, email: string, role: ProjectRole) =>
    apiSend<ProjectMember>('POST', `/api/projects/${projectId}/members`, { email, role }),
  updateRole: (projectId: string, memberId: string, role: ProjectRole) =>
    apiSend<ProjectMember>('PATCH', `/api/projects/${projectId}/members/${memberId}`, { role }),
  remove: (projectId: string, memberId: string) =>
    apiSend<void>('DELETE', `/api/projects/${projectId}/members/${memberId}`),
}

/** The signed-in user's notification feed. */
export const notificationApi = {
  list: (opts?: ReadOpts) => apiGet<AppNotification[]>('/api/notifications', opts),
  unreadCount: (opts?: ReadOpts) => apiGet<{ count: number }>('/api/notifications/unread-count', opts),
  markRead: (id: string) => apiSend<void>('POST', `/api/notifications/${id}/read`),
  markAllRead: () => apiSend<void>('POST', '/api/notifications/read-all'),
}

export type ProjectAnalytics = {
  totalRuns: number; completedRuns: number; failedRuns: number; totalMessages: number
  totalTokens: number; fileCount: number; deploymentStatus: DeploymentStatus
  createdAt: string; lastActivityAt: string
}
export type SecurityFinding = { severity: 'HIGH' | 'MEDIUM' | 'LOW'; title: string; file: string; detail: string }
export type SecurityScanResult = { scannedFiles: number; high: number; medium: number; low: number; findings: SecurityFinding[] }

/** Real project insights (analytics + static security scan). */
export const insightsApi = {
  analytics: (projectId: string, opts?: ReadOpts) => apiGet<ProjectAnalytics>(`/api/projects/${projectId}/analytics`, opts),
  securityScan: (projectId: string, opts?: ReadOpts) => apiGet<SecurityScanResult>(`/api/projects/${projectId}/security-scan`, opts),
}

/** Read-only browse of a project's agent workspace files (code panel). */
export const filesApi = {
  tree: (projectId: string, opts?: ReadOpts) => apiGet<FileNode[]>(`/api/projects/${projectId}/files`, opts),
  content: (projectId: string, path: string, opts?: ReadOpts) =>
    apiGet<FileContent>(`/api/projects/${projectId}/files/content?path=${encodeURIComponent(path)}`, opts),
}

export async function startProject(
  prompt: string | null,
  mode: ProjectType = 'BUILD',
  workspaceId?: string,
  opts?: { idempotencyKey?: string; files?: File[] },
): Promise<Project> {
  let targetId = workspaceId
  if (!targetId) {
    const workspaces = await workspaceApi.list()
    targetId = (workspaces.find((item) => item.personal) ?? workspaces[0])?.id
  }
  if (!targetId) throw new Error('No workspace available')
  const files = opts?.files ?? []
  if (prompt && files.length) {
    // Attachments need a project to upload into, and the agent must see them with the first turn:
    // create the project, upload, then send the prompt with the uploaded paths.
    const project = await projectApi.create(targetId, { type: mode, name: prompt.slice(0, 60) }, opts)
    const uploaded = await projectApi.uploadAttachments(project.id, files)
    await messageApi.post(project.id, prompt, mode, uploaded.map((file) => file.path))
    return project
  }
  return projectApi.create(targetId, {
    type: mode,
    ...(prompt ? { initialMessage: { id: newUserMessageId(), message: prompt, mode, aiMessageId: newAiMessageId() } } : {}),
  }, opts)
}

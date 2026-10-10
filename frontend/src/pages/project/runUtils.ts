import { type AgentRun, type AgentStep, type Message, type Project } from '../../api/projects'
import { FILE_CHANGE_KINDS } from './activity'

export function isActive(run: AgentRun) {
  return run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'AWAITING_INPUT'
}

export function needsRequirementInterview(project: Project | null): boolean {
  if (!project || project.type !== 'BUILD' || project.lifecycleStage !== 'REQUIREMENTS') return false
  const prompt = (project.initialPrompt ?? '').trim().toLowerCase()
  if (!prompt || /^(hi|hello|hey|yo|good morning|good evening|ok|okay)[.! ]*$/.test(prompt)) return false
  return prompt.length >= 9 && /\b(build|create|make|develop|design|implement|generate|clone|app|application|website|dashboard|todo|tool|platform|system|project|page|portal|crm|portfolio|store|shop|blog|mern)\b/.test(prompt)
}

export function isInternalWorkflowMessage(message: Message): boolean {
  return message.role === 'USER' && message.content.startsWith('Build the requested project now. Use the approved requirements below as the source of truth.')
}

export function formatMessageTime(iso: string): string {
  const date = new Date(iso)
  const day = date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  const time = date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  return `${day} at ${time}`
}

const BUILD_TOOL_NAMES = new Set(['createFile', 'createFiles', 'patchFile', 'deleteFile', 'renameFile', 'copyFile'])

/** True once the run has actually started writing files, not merely exploring/reading them. */
export function hasBuildActivity(steps: AgentStep[]): boolean {
  return steps.some((s) => s.activity
    ? FILE_CHANGE_KINDS.has(s.activity.kind)
    : (s.type === 'TOOL_CALL' || s.type === 'TOOL_RESULT') && !!s.content && BUILD_TOOL_NAMES.has(s.content))
}

export function formatDuration(run: AgentRun): string {
  const seconds = secondsBetween(run.createdAt, new Date(run.updatedAt).getTime())
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

/** Rough proxy until real usage-based billing exists: 1 credit per 1000 prompt+completion tokens. */
export function creditsUsed(run: AgentRun): string {
  return ((run.promptTokens + run.completionTokens) / 1000).toFixed(1)
}

export function changedFileCount(steps: AgentStep[]): number {
  const step = steps.find((s) => s.type === 'STATUS' && s.content === 'Change set committed')
  if (!step?.data) return 0
  try {
    const data = JSON.parse(step.data) as { changedFiles?: unknown }
    return Array.isArray(data.changedFiles) ? data.changedFiles.length : 0
  } catch {
    return 0
  }
}

export function combinedReasoning(steps: AgentStep[]): string | null {
  const parts = steps.filter((s) => s.type === 'THINKING').map(reasoningFromStep).filter((s): s is string => !!s)
  return parts.length ? parts.join('\n\n') : null
}

export function secondsBetween(fromIso: string, toMs: number): number {
  return Math.max(0, Math.round((toMs - new Date(fromIso).getTime()) / 1000))
}

/* ── Version history (Figma 18) — every agent run, newest first, with per-run restore ─────────── */
/** How a run's status reads: its label and a tone (coloured by the .pv-chip--<tone> styles). */
export type StatusTone = 'ok' | 'busy' | 'wait' | 'failed' | 'idle'
export const RUN_STATUS_META: Record<string, { label: string; tone: StatusTone }> = {
  DONE: { label: 'Complete', tone: 'ok' },
  RUNNING: { label: 'Running', tone: 'busy' },
  QUEUED: { label: 'Queued', tone: 'wait' },
  AWAITING_INPUT: { label: 'Awaiting input', tone: 'wait' },
  FAILED: { label: 'Failed', tone: 'failed' },
  CANCELLED: { label: 'Cancelled', tone: 'idle' },
}
export const runStatus = (status: string) => RUN_STATUS_META[status] ?? { label: status, tone: 'idle' as StatusTone }

export function runWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export function friendlyToolTitle(tool: string | null): string {
  switch (tool) {
    case 'setPlan': return 'Created implementation plan'
    case 'updatePlanStep': return 'Updated implementation plan'
    case 'listFiles': return 'Read project files'
    case 'readFile': return 'Read file'
    case 'getFileSummary': return 'Summarized file'
    case 'searchCode': return 'Searched code'
    case 'getProjectMetadata': return 'Read project metadata'
    case 'createFile': return 'Created file'
    case 'writeFile': return 'Wrote file'
    case 'patchFile': return 'Edited file'
    case 'deleteFile': return 'Deleted file'
    case 'renameFile': return 'Renamed file'
    case 'getDiagnostics': return 'Checked diagnostics'
    case 'runTypeCheck': return 'Checked types'
    case 'runBuild': return 'Checked production build'
    case 'runTests': return 'Ran tests'
    case 'validateChanges': return 'Validated changes'
    default: return tool ? tool.replace(/([a-z])([A-Z])/g, '$1 $2') : 'Agent step'
  }
}

export function fileFromStep(step: AgentStep): string | null {
  if (!step.data) return null
  try {
    const data = JSON.parse(step.data) as { path?: string }
    return typeof data.path === 'string' ? data.path : null
  } catch {
    return null
  }
}

export function reasoningFromStep(step: AgentStep): string | null {
  if (step.data) {
    try {
      const data = JSON.parse(step.data) as { reasoning?: string }
      if (typeof data.reasoning === 'string' && data.reasoning.trim()) return data.reasoning
    } catch {
      // not JSON
    }
  }
  if (typeof step.content === 'string' && step.content.trim() && step.content !== 'Model reasoning') {
    return step.content
  }
  return null
}

import type { AgentRun } from '../../../api/projects'
import { isActive } from '../runUtils'

/** Presentation comes from persisted outcomes, never from file counts or optimistic model prose. */
export function buildReply(run: AgentRun) {
  if (run.saveRecoveryRequired) return { title: 'Checking whether your changes were saved', text: 'The save needs recovery verification. Open Details for its status.', preview: false }
  if (run.codeSavedAt) {
    if (run.previewStatus === 'READY') return { title: 'Application updated', text: 'Your changes are saved. The preview is ready.', preview: true }
    if (run.previewStatus === 'FAILED') return { title: 'Changes saved; preview needs attention', text: 'Your changes were saved, but the preview could not be prepared. Open Details to review the problem.', preview: false }
    return { title: 'Changes saved', text: run.previewStatus === 'BUILDING' ? 'Preparing your preview…' : 'The preview is not ready yet.', preview: false }
  }
  if (isActive(run)) return { title: run.status === 'QUEUED' ? 'Build queued' : 'Building your application', text: run.status === 'QUEUED' ? 'Your build will start when a worker is available.' : run.status === 'COMMITTING' ? 'Saving the checked changes…' : 'Working on your request and checking the result…', preview: false }
  if (run.status === 'CANCELLED') return { title: 'Build stopped', text: 'No new changes were saved. Your previous application is still available.', preview: false }
  if (run.failureReason === 'RUN_TOKEN_BUDGET_EXCEEDED') return { title: 'Build token budget reached', text: 'This build reached its token budget. No new changes were saved. Try a smaller request or retry the unfinished draft.', preview: false }
  if (run.failureReason === 'DAILY_QUOTA_EXCEEDED') return { title: 'Daily AI limit reached', text: 'Your daily AI allowance is used. No new changes were saved.', preview: false }
  if (run.failureReason === 'RUN_LIMIT_REACHED') return { title: 'Build limit reached', text: 'This build reached its execution limit. No new changes were saved. Open Details to review it.', preview: false }
  if (run.failureReason === 'PROVIDER_REQUEST_REJECTED') return { title: 'AI request could not be processed', text: 'The AI provider rejected this request. No new changes were saved. Open Details to review it.', preview: false }
  return { title: 'Build could not be completed', text: 'No new changes were saved. Open Details to review what needs attention.', preview: false }
}

/** Old messages may contain file inventories; keep those in the technical views. */
export function readableBuildText(content: string): string {
  const withoutInventory = content.split(/\n\s*(?:changed files|files changed|created files|modified files)\s*:/i)[0]
  return withoutInventory.split('\n').filter(line => !/```|(?:[\w.-]+\/)+[\w.-]+|\b[\w.-]+\.(?:tsx?|jsx?|json|css|html|sql|ya?ml|md)\b/i.test(line)).join('\n').trim().slice(0, 900)
}

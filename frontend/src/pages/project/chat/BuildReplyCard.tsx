import { useRef, useState } from 'react'
import { agentApi, type AgentRun } from '../../../api/projects'
import { newAiMessageId, newUserMessageId } from '../../../lib/ids'
import { ApiClientError } from '../../../auth/api'
import { buildReply } from './buildReply'

/** Failed builds that a retry can continue: nothing saved, and not stopped by quota or a review requirement. */
function retryable(run: AgentRun) {
  return run.status === 'FAILED' && !run.codeSavedAt && !run.saveRecoveryRequired
    && run.failureReason !== 'DAILY_QUOTA_EXCEEDED' && run.failureReason !== 'USER_REVIEW_REQUIRED'
}

/** The normal build surface; file activity remains available through Details. */
export function BuildReplyCard({ run, projectId, explanation, currentPreview = true, onOpenDetails, onOpenPreview, onRetried }: {
  run: AgentRun; projectId?: string; explanation?: string; currentPreview?: boolean
  onOpenDetails: () => void; onOpenPreview: () => void; onRetried?: (run: AgentRun) => void
}) {
  const outcome = buildReply(run)
  const [retrying, setRetrying] = useState(false)
  const [retryError, setRetryError] = useState<string>()
  const [forbidden, setForbidden] = useState(false)
  // Kept across ambiguous failures so clicking again cannot start a second build.
  const retryRequest = useRef({ id: newUserMessageId(), aiMessageId: newAiMessageId() })
  const failed = run.status === 'FAILED' && !run.codeSavedAt && !run.saveRecoveryRequired
  const text = failed && explanation?.trim() ? explanation.trim().slice(0, 900) : outcome.text
  const canRetry = !!projectId && !!onRetried && retryable(run) && !forbidden
  const retry = async () => {
    if (!projectId || !onRetried) return
    setRetrying(true); setRetryError(undefined)
    try { onRetried(await agentApi.retryFailedDraft(projectId, run.id, retryRequest.current)) }
    catch (e) {
      // Viewers cannot start builds; the server refuses, so stop offering the action.
      if (e instanceof ApiClientError && e.status === 403) { setForbidden(true); setRetryError('Only editors can retry this build.') }
      else setRetryError(e instanceof Error ? e.message : 'Could not retry this build.')
    }
    finally { setRetrying(false) }
  }
  return <section className="msg-build-card" aria-label="Build result" data-run-id={run.id}>
    <b>{outcome.title}</b>
    <p role="status" style={{ whiteSpace: 'pre-line' }}>{outcome.preview && !currentPreview ? 'This is an earlier saved build. Open Details to inspect it.' : text}</p>
    {retryError && <p role="alert">{retryError}</p>}
    <div className="msg-build-card__actions">
      {canRetry && <button type="button" onClick={() => void retry()} disabled={retrying}>{retrying ? 'Retrying…' : 'Retry'}</button>}
      <button type="button" onClick={onOpenDetails}>Details</button>
      <button type="button" onClick={onOpenPreview} disabled={!outcome.preview || !currentPreview} title={outcome.preview && currentPreview ? 'Open current saved preview' : 'A ready current saved preview is required'}>Preview</button>
    </div>
  </section>
}

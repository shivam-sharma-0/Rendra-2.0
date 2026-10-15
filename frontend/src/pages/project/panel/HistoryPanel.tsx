import { type AgentRun } from '../../../api/projects'
import { useState } from 'react'
import { FailedDraftPanel } from './FailedDraftPanel'
import { NeuButton } from '../../../components/neu'
import { EmptyState } from '../../../components/ui/States'
import { isActive, runOutcome, runWhen } from '../runUtils'

/** Every run of the project, newest first; the latest restorable build can be restored. */
export function HistoryPanel({ projectId, runs, onRestore, rollingBackId, onRetried }: { projectId: string; runs: AgentRun[]; onRestore: (id: string) => void; rollingBackId: string | null; onRetried: (run: AgentRun) => void }) {
  const [draftRunId, setDraftRunId] = useState<string | null>(null)
  if (!runs.length) return <EmptyState title="No builds yet" hint="Ask Rendra to build something and each run will appear here." />
  return (
    <div className="pv-page">
      <p className="pv-sub">{runs.length} run{runs.length === 1 ? '' : 's'}</p>
      <ol className="pv-list">
        {runs.map((run) => {
          const status = runOutcome(run)
          const tokens = run.promptTokens + run.completionTokens
          const canRestore = run.mode === 'BUILD' && run.rollbackAvailable && !isActive(run)
          return (
            <li className="pv-card pv-run" key={run.id}>
              <span className="pv-mode">{run.mode.toLowerCase()}</span>
              <div className="pv-run__main">
                <b>{run.conversational ? 'Chat reply' : `${run.mode.charAt(0)}${run.mode.slice(1).toLowerCase()} run`}</b>
                <small>{runWhen(run.createdAt)}{tokens > 0 && ` · ${tokens.toLocaleString()} tokens`}</small>
              </div>
              <span className={`pv-chip pv-chip--${status.tone}`}>{status.label}</span>
              {run.mode === 'BUILD' && !run.codeSavedAt && !run.saveRecoveryRequired && (run.status === 'FAILED' || run.status === 'CANCELLED') && (
                <NeuButton size="sm" onClick={() => setDraftRunId((current) => current === run.id ? null : run.id)} aria-expanded={draftRunId === run.id}>
                  {draftRunId === run.id ? 'Hide draft' : 'Inspect draft'}
                </NeuButton>
              )}
              {canRestore && (
                <NeuButton size="sm" onClick={() => onRestore(run.id)} disabled={rollingBackId === run.id}>
                  {rollingBackId === run.id ? 'Restoring…' : 'Restore'}
                </NeuButton>
              )}
            </li>
          )
        })}
      </ol>
      {draftRunId && <FailedDraftPanel key={draftRunId} projectId={projectId} runId={draftRunId} onRetried={onRetried} />}
    </div>
  )
}

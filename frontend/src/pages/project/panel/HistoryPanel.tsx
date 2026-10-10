import { type AgentRun } from '../../../api/projects'
import { NeuButton } from '../../../components/neu'
import { EmptyState } from '../../../components/ui/States'
import { runStatus, runWhen } from '../runUtils'

/** Every run of the project, newest first; the latest restorable build can be restored. */
export function HistoryPanel({ runs, onRestore, rollingBackId }: { runs: AgentRun[]; onRestore: (id: string) => void; rollingBackId: string | null }) {
  if (!runs.length) return <EmptyState title="No builds yet" hint="Ask Rendra to build something and each run will appear here." />
  return (
    <div className="pv-page">
      <p className="pv-sub">{runs.length} run{runs.length === 1 ? '' : 's'}</p>
      <ol className="pv-list">
        {runs.map((run) => {
          const status = runStatus(run.status)
          const tokens = run.promptTokens + run.completionTokens
          const canRestore = run.mode === 'BUILD' && run.rollbackAvailable
          return (
            <li className="pv-card pv-run" key={run.id}>
              <span className="pv-mode">{run.mode.toLowerCase()}</span>
              <div className="pv-run__main">
                <b>{run.conversational ? 'Chat reply' : `${run.mode.charAt(0)}${run.mode.slice(1).toLowerCase()} run`}</b>
                <small>{runWhen(run.createdAt)}{tokens > 0 && ` · ${tokens.toLocaleString()} tokens`}</small>
              </div>
              <span className={`pv-chip pv-chip--${status.tone}`}>{status.label}</span>
              {canRestore && (
                <NeuButton size="sm" onClick={() => onRestore(run.id)} disabled={rollingBackId === run.id}>
                  {rollingBackId === run.id ? 'Restoring…' : 'Restore'}
                </NeuButton>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

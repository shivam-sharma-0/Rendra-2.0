import { type AgentRun } from '../../../api/projects'
import { RUN_STATUS_META, runWhen } from '../runUtils'

export function HistoryPanel({ runs, onRestore, rollingBackId }: { runs: AgentRun[]; onRestore: (id: string) => void; rollingBackId: string | null }) {
  if (!runs.length) return <div className="pw-emptyfiles">No builds yet — ask Rendra to build something and each run will appear here.</div>
  return (
    <div className="pw-history">
      {runs.map((run) => {
        const meta = RUN_STATUS_META[run.status] ?? { label: run.status, color: '#6E6B74' }
        const tokens = run.promptTokens + run.completionTokens
        const canRestore = run.mode === 'BUILD' && run.rollbackAvailable
        return (
          <div className="pw-hrow" key={run.id}>
            <span className="pw-hmode">{run.mode}</span>
            <div className="pw-hmain">
              <b>{meta.label} · {run.mode.toLowerCase()} run</b>
              <small>{runWhen(run.createdAt)}{tokens > 0 && ` · ${tokens.toLocaleString()} tokens`}</small>
            </div>
            <span className="pw-hstatus" style={{ color: meta.color }}>{meta.label}</span>
            {canRestore && (
              <button className="pw-hrestore" onClick={() => onRestore(run.id)} disabled={rollingBackId === run.id}>
                {rollingBackId === run.id ? 'Restoring…' : 'Restore'}
              </button>
            )}
          </div>
        )
      })}
    </div>
  )
}

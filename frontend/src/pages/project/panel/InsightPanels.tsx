import { useAnalytics, useDatabase, useSecurityScan } from '../../../api/queries'
import { EmptyState, ErrorState, LoadingState } from '../../../components/ui/States'

/* ── Analytics (Figma 21) — real activity aggregates ──────────────────────────────────────────── */
export function AnalyticsPanel({ projectId, active }: { projectId: string; active: boolean }) {
  const { data, isLoading, isError, refetch } = useAnalytics(projectId, active)
  if (isLoading) return <div className="pw-insight"><LoadingState /></div>
  if (isError || !data) return <div className="pw-insight"><ErrorState message="Couldn't load analytics." onRetry={() => void refetch()} /></div>
  const rate = data.totalRuns ? Math.round((data.completedRuns / data.totalRuns) * 100) : 0
  const stats: [string, string | number][] = [
    ['Builds', data.totalRuns], ['Completed', data.completedRuns], ['Failed', data.failedRuns],
    ['Success rate', `${rate}%`], ['Messages', data.totalMessages], ['Files', data.fileCount],
    ['Tokens used', data.totalTokens.toLocaleString()], ['Status', data.deploymentStatus],
  ]
  return (
    <div className="pw-insight">
      <h2>Analytics</h2>
      <p className="pw-insight-sub">Real activity for this project.</p>
      <div className="pw-stat-grid">
        {stats.map(([label, val]) => <div className="pw-stat" key={label}><span>{label}</span><b>{val}</b></div>)}
      </div>
      <p className="pw-insight-meta">Created {new Date(data.createdAt).toLocaleDateString()} · Last activity {new Date(data.lastActivityAt).toLocaleDateString()}</p>
    </div>
  )
}

/* ── Security scan (Figma 23) — real static scan of workspace files ────────────────────────────── */
export function SecurityPanel({ projectId, active }: { projectId: string; active: boolean }) {
  const { data, isLoading, isError, refetch } = useSecurityScan(projectId, active)
  if (isLoading) return <div className="pw-insight"><LoadingState label="Scanning workspace…" /></div>
  if (isError || !data) return <div className="pw-insight"><ErrorState message="Couldn't run the security scan." onRetry={() => void refetch()} /></div>
  return (
    <div className="pw-insight">
      <h2>Security scan</h2>
      <p className="pw-insight-sub">Static check of {data.scannedFiles} workspace file{data.scannedFiles === 1 ? '' : 's'}.</p>
      <div className="pw-sec-summary">
        <span className="pw-sev high">{data.high} high</span>
        <span className="pw-sev medium">{data.medium} medium</span>
        <span className="pw-sev low">{data.low} low</span>
      </div>
      {data.findings.length === 0
        ? <EmptyState title="No issues found" hint="No secrets or risky patterns detected in the scanned files." />
        : <div className="pw-sec-list">
            {data.findings.map((f, i) => (
              <div className="pw-sec-row" key={i}>
                <span className={`pw-sev ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div><b>{f.title}</b><small>{f.file} · {f.detail}</small></div>
              </div>
            ))}
          </div>}
    </div>
  )
}

/* ── Cloud database (Figma 22) — honest empty state (no managed DB backend yet) ────────────────── */
export function DatabasePanel({ projectId }: { projectId: string }) {
  const { data, isLoading, isError, refetch } = useDatabase(projectId)
  if (isLoading) return <div className="pw-insight"><LoadingState /></div>
  if (isError || !data) return <div className="pw-insight"><ErrorState message="Could not load database status." onRetry={() => void refetch()} /></div>
  return (
    <div className="pw-insight">
      <h2>Project database</h2>
      <p className="pw-insight-sub">The MongoDB namespace reserved for this generated app.</p>
      <div className="pw-db-status"><span className={'pw-sev ' + (data.reachable ? 'low' : 'medium')}>{data.reachable ? 'Connected' : 'Unavailable'}</span><code>{data.database}</code></div>
      {data.reachable ? <div className="pw-db-collections">{data.collections.length ? data.collections.map((name) => <span key={name}>{name}</span>) : <span>No collections yet</span>}</div> : <EmptyState title="MongoDB is unavailable" hint="Start the local MongoDB service to inspect this project's data." />}
    </div>
  )
}

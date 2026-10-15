import { useAnalytics, useDatabase, useSecurityScan } from '../../../api/queries'
import { EmptyState, ErrorState, LoadingState } from '../../../components/ui/States'

/** Real activity aggregates for the project. */
export function AnalyticsPanel({ projectId, active }: { projectId: string; active: boolean }) {
  const { data, isLoading, isError, refetch } = useAnalytics(projectId, active)
  if (isLoading) return <LoadingState />
  if (isError || !data) return <ErrorState message="Couldn't load analytics." onRetry={() => void refetch()} />
  const rate = data.totalRuns ? Math.round((data.completedRuns / data.totalRuns) * 100) : 0
  const stats: [string, string | number][] = [
    ['Builds', data.totalRuns], ['Completed', data.completedRuns], ['Failed', data.failedRuns],
    ['Success rate', `${rate}%`], ['Messages', data.totalMessages], ['Files', data.fileCount],
    ['Tokens charged or held', data.totalTokens.toLocaleString()], ['Status', data.deploymentStatus.toLowerCase()],
    ...(data.reportedTokens === undefined ? [] : [
      ['Reported tokens', data.reportedTokens.toLocaleString()], ['Unreported usage held', (data.unknownTokens ?? 0).toLocaleString()],
      ['Active reservations', (data.reservedTokens ?? 0).toLocaleString()], ['Provider attempts', data.actualAttempts ?? 0],
      ['Logical calls', data.logicalCalls ?? 0], ['Historical attempt records', data.historicalAttemptRecords ?? 0],
    ] as [string, string | number][]),
  ]
  return (
    <div className="pv-page">
      <header className="pv-head"><h3>Analytics</h3></header>
      <p className="pv-sub">Real activity for this project.</p>
      <dl className="pv-stats">
        {stats.map(([label, value]) => <div className="pv-stat" key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
      </dl>
      <p className="pv-sub">Created {new Date(data.createdAt).toLocaleDateString()} · Last activity {new Date(data.lastActivityAt).toLocaleDateString()}</p>
    </div>
  )
}

/** A static scan of the workspace files for secrets and risky patterns. */
export function SecurityPanel({ projectId, active }: { projectId: string; active: boolean }) {
  const { data, isLoading, isError, refetch } = useSecurityScan(projectId, active)
  if (isLoading) return <LoadingState label="Scanning workspace…" />
  if (isError || !data) return <ErrorState message="Couldn't run the security scan." onRetry={() => void refetch()} />
  return (
    <div className="pv-page">
      <header className="pv-head"><h3>Security scan</h3></header>
      <p className="pv-sub">Static check of {data.scannedFiles} workspace file{data.scannedFiles === 1 ? '' : 's'}.</p>
      <div className="pv-chips">
        <span className="pv-chip pv-chip--failed">{data.high} high</span>
        <span className="pv-chip pv-chip--wait">{data.medium} medium</span>
        <span className="pv-chip pv-chip--busy">{data.low} low</span>
      </div>
      {data.findings.length === 0
        ? <EmptyState title="No issues found" hint="No secrets or risky patterns detected in the scanned files." />
        : (
          <ol className="pv-list">
            {data.findings.map((f, i) => (
              <li className="pv-card pv-finding" key={i}>
                <span className={`pv-chip pv-chip--${f.severity === 'HIGH' ? 'failed' : f.severity === 'MEDIUM' ? 'wait' : 'busy'}`}>{f.severity.toLowerCase()}</span>
                <div><b>{f.title}</b><small>{f.file} · {f.detail}</small></div>
              </li>
            ))}
          </ol>
        )}
    </div>
  )
}

/** The MongoDB namespace reserved for the generated app, and its collections. */
export function DatabasePanel({ projectId }: { projectId: string }) {
  const { data, isLoading, isError, refetch } = useDatabase(projectId)
  if (isLoading) return <LoadingState />
  if (isError || !data) return <ErrorState message="Could not load database status." onRetry={() => void refetch()} />
  return (
    <div className="pv-page">
      <header className="pv-head"><h3>Project database</h3></header>
      <p className="pv-sub">The MongoDB namespace reserved for this generated app.</p>
      <div className="pv-chips">
        <span className={`pv-chip pv-chip--${data.reachable ? 'ok' : 'wait'}`}>{data.reachable ? 'Connected' : 'Unavailable'}</span>
        <code className="pv-code">{data.database}</code>
      </div>
      {data.reachable
        ? (
          <div className="pv-well pv-collections">
            <h4>Collections</h4>
            {data.collections.length ? <ul>{data.collections.map((name) => <li key={name}><code>{name}</code></li>)}</ul> : <p className="pv-note">No collections yet.</p>}
          </div>
        )
        : <EmptyState title="MongoDB is unavailable" hint="Start the local MongoDB service to inspect this project's data." />}
    </div>
  )
}

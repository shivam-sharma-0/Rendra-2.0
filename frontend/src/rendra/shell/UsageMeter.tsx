import { Link } from 'react-router-dom'
import { useDailyUsage } from '../../api/queries'
import { cx } from '../../lib/cx'
import { ICON } from '../icons'
import ShellIcon from './ShellIcon'

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })
const NEAR_LIMIT = 90 // percent

/** Today's AI usage against the daily quota, from /api/usage/daily. Only fetched while shown. */
export default function UsageMeter({ onNavigate }: { onNavigate: () => void }) {
  const { data, isError } = useDailyUsage()
  const unlimited = data?.quota === 0
  const percent = data && !unlimited ? Math.min(100, Math.round((data.tokensUsed / data.quota) * 100)) : 0
  const resetsAt = data ? new Date(data.resetsAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : ''

  let summary = 'Loading usage…'
  if (isError) summary = 'Usage is unavailable right now'
  else if (data && unlimited) summary = `${compact.format(data.tokensUsed)} tokens used · no daily limit`
  else if (data) summary = `${compact.format(data.tokensUsed)} of ${compact.format(data.quota)} tokens · resets ${resetsAt}`

  return (
    <div className="kap-ws-credits">
      <div className="kap-ws-credits-head">
        <span className="kap-ws-credits-title">Daily AI usage</span>
        <Link to="/settings/billing-and-usage" className="kap-ws-credits-badge" onClick={onNavigate}>
          {data && !unlimited ? `${percent}% used` : 'Details'} <ShellIcon d={ICON.chevronRight} size={12} />
        </Link>
      </div>
      <div className="kap-ws-progress" role="progressbar" aria-label="Daily AI usage"
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={data && !unlimited ? percent : undefined}>
        <div className={cx('kap-ws-progress-fill', percent >= NEAR_LIMIT && 'is-near-limit')} style={{ width: `${percent}%` }} />
      </div>
      <div className="kap-ws-credits-reset"><span className="kap-ws-bullet" /><span>{summary}</span></div>
    </div>
  )
}

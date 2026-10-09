import { type AgentRun } from '../../../api/projects'
import { cx } from '../../../lib/cx'
import { timeAgo } from '../../../lib/time'
import { isActive } from '../runUtils'
import { useTicker } from '../useTicker'

/** One-line state of the project's latest build, shown next to its name. Nothing before the first build. */
export function BuildStatus({ runs }: { runs: AgentRun[] }) {
  // Runs come newest first; answers in chat mode are not builds.
  const build = runs.find((run) => run.mode === 'BUILD' && !run.conversational)
  useTicker(build?.status === 'DONE', 60_000) // keeps "Built x minutes ago" current
  if (!build) return null

  const [tone, text] = isActive(build) ? ['busy', 'Building…']
    : build.status === 'DONE' ? ['ok', `Built ${timeAgo(build.updatedAt)}`]
      : build.status === 'FAILED' ? ['failed', 'Build failed']
        : ['idle', 'Build stopped']

  return (
    <span className={cx('ws-build', `ws-build--${tone}`)} aria-live="polite">
      <i aria-hidden />
      {text}
    </span>
  )
}

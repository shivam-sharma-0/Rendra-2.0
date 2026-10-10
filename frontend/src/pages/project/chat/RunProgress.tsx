import { useState, type MouseEvent } from 'react'
import { type ActivityKind, type AgentActivity, type AgentRun, type AgentStep } from '../../../api/projects'
import { AppIcon, type AppIconName } from '../../../components/icons'
import { cx } from '../../../lib/cx'
import { timelineEntries } from '../activity'
import { fileFromStep, friendlyToolTitle, reasoningFromStep, secondsBetween } from '../runUtils'
import { useTicker } from '../useTicker'
import { Markdown } from './Markdown'
import './run-steps.css'

/** The file-write checklist, embedded in the reply bubble in place of the plain thinking ticker —
 * shown only once the run has actually started writing files (see hasBuildActivity). */
export function BuildProgress({ run, steps }: { run: AgentRun; steps: AgentStep[] }) {
  // A check that is still running already shows its own spinner.
  const last = steps[steps.length - 1]
  const checking = last?.type === 'TOOL_CALL' && last.activity?.kind === 'CHECK'
  return (
    <div className="msg-build">
      <b><span className="msg-build__orb" />Building…</b>
      <div className="run-steps">
        <RunTimeline steps={steps} startedAt={run.createdAt} />
        {!checking && <ThinkingNow since={last?.createdAt ?? run.createdAt} />}
      </div>
    </div>
  )
}

/** A run's user-facing timeline (chat build card and Details panel): thinking, files, checks, outcomes. */
export function RunTimeline({ steps, startedAt }: { steps: AgentStep[]; startedAt: string }) {
  return (
    <>
      {timelineEntries(steps, startedAt).map((entry) => entry.type === 'thinking'
        ? <ThinkingStep key={entry.key} step={entry.step} previousCreatedAt={entry.since} />
        : entry.type === 'activity'
          ? <ActivityRow key={entry.key} activity={entry.activity} />
          : <BuildStep key={entry.key} step={entry.step} />)}
    </>
  )
}

const ACTIVITY_ICONS: Partial<Record<ActivityKind, AppIconName>> = {
  READ: 'file', CREATE: 'plus', EDIT: 'newProject', DELETE: 'trash', MOVE: 'copy', PLAN: 'listBullets',
  REVIEW: 'details', ATTACHMENTS: 'files', SAVED: 'check', PREVIEW: 'globe', STATUS: 'clock',
  STOPPED: 'close', FAILED: 'warning', ASK_USER: 'chat', MESSAGE: 'chat',
}
const VISIBLE_PATHS = 3

function activityIcon(activity: AgentActivity): AppIconName {
  if (activity.kind === 'CHECK') return activity.ok === false ? 'warning' : 'check'
  if (activity.ok === false && activity.kind !== 'STOPPED') return 'warning'
  return ACTIVITY_ICONS[activity.kind] ?? 'clock'
}

/** One activity: icon, line, the files it touched (first few, the rest on demand), a detail line and plan steps. */
function ActivityRow({ activity }: { activity: AgentActivity }) {
  const [allPaths, setAllPaths] = useState(false)
  const running = activity.kind === 'CHECK' && activity.ok === null
  const paths = allPaths ? activity.paths : activity.paths.slice(0, VISIBLE_PATHS)
  const hidden = activity.paths.length - paths.length
  const tone = activity.ok === false ? 'bad' : activity.kind === 'CHECK' || activity.kind === 'SAVED' ? (activity.ok ? 'good' : null) : null
  return (
    <div className={cx('run-step run-activity', tone && `run-activity--${tone}`, running && 'is-running')}>
      <span className="run-activity__icon" aria-hidden>{running ? <span className="msg-spin" /> : <AppIcon name={activityIcon(activity)} />}</span>
      <span className="run-activity__body">
        <span className="run-activity__line">
          <span className="run-step__label">{activity.title}</span>
          {paths.map((path) => <code key={path} title={path}>{path}</code>)}
          {hidden > 0 && <button type="button" className="run-activity__more" onClick={() => setAllPaths(true)}>+{hidden} more</button>}
        </span>
        {activity.detail && <small className="run-activity__detail">{activity.detail}</small>}
        {activity.items.length > 0 && <ol className="run-activity__items">{activity.items.map((item, i) => <li key={i}>{item}</li>)}</ol>}
      </span>
    </div>
  )
}

/** Live-ticking "Thinking for Ns…" line shown while the model hasn't produced its next step yet. */
function ThinkingNow({ since }: { since: string }) {
  useTicker(true)
  return <div className="run-step run-step--now"><span className="msg-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** The default pending state for a plain chat/explain reply: just a ticking "Thinking for Ns…" line,
 * no build checklist — that only appears once hasBuildActivity is true (see BuildProgress). A run
 * queued behind another run in the project says so instead. */
export function ThinkingLine({ since, queued = false }: { since: string; queued?: boolean }) {
  useTicker(!queued)
  if (queued) return <div className="msg-thinking"><AppIcon name="clock" /> Waiting for the current run to finish…</div>
  return <div className="msg-thinking"><span className="msg-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** A step from a server that does not send activities (older runs are mapped by the server, so this is a fallback). */
export function BuildStep({ step, previousCreatedAt }: { step: AgentStep; previousCreatedAt?: string }) {
  if (step.type === 'THINKING') return <ThinkingStep step={step} previousCreatedAt={previousCreatedAt} />
  const title = step.type === 'TOOL_CALL' ? friendlyToolTitle(step.content)
    : step.type === 'TOOL_RESULT' ? `${friendlyToolTitle(step.content)} complete`
      : step.type === 'STATUS' ? (step.content ?? 'Status')
        : step.type.replace('_', ' ')
  const file = fileFromStep(step)
  return (
    <div className="run-step">
      <span className="run-step__dot" />
      <span className="run-step__label">{title}</span>
      {file && <code>{file}</code>}
    </div>
  )
}

/** A "Thought for Ns" step expands in place to show the model's reasoning. */
function ThinkingStep({ step, previousCreatedAt }: { step: AgentStep; previousCreatedAt?: string }) {
  const [expanded, setExpanded] = useState(false)
  const [copied, setCopied] = useState(false)
  const reasoning = reasoningFromStep(step)
  const seconds = secondsBetween(previousCreatedAt ?? step.createdAt, new Date(step.createdAt).getTime())

  const copyReasoning = (e: MouseEvent) => {
    e.stopPropagation()
    if (!reasoning) return
    void navigator.clipboard.writeText(reasoning)
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  return (
    <div className="run-thought">
      <button
        type="button"
        className={`run-thought__chip${expanded ? ' is-open' : ''}`}
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        title={expanded ? 'Collapse thinking' : 'Expand thinking'}
      >
        <AppIcon name="brain" />
        <span>Thought for {seconds}s</span>
        <span className="run-thought__chev"><AppIcon name="caretRight" /></span>
      </button>
      {expanded && (
        <div className="run-thought__box">
          <div className="run-thought__head">
            <span>Reasoning · {seconds}s</span>
            {reasoning && (
              <button type="button" onClick={copyReasoning} title={copied ? 'Copied to clipboard' : 'Copy reasoning'}>
                <AppIcon name={copied ? 'check' : 'copy'} />
                <span>{copied ? 'Copied' : 'Copy'}</span>
              </button>
            )}
          </div>
          <div className="run-thought__body">
            {reasoning ? <Markdown content={reasoning} /> : <p className="run-thought__empty">No reasoning was streamed for this response.</p>}
          </div>
        </div>
      )}
    </div>
  )
}

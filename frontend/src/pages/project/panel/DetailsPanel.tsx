import { type AgentRun, type AgentStep, type Project, type RequirementSession } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { EmptyState } from '../../../components/ui/States'
import { cx } from '../../../lib/cx'
import { RunTimeline } from '../chat/RunProgress'
import { runOutcome, runWhen } from '../runUtils'

const STAGES = [
  ['REQUIREMENTS', 'Requirements'], ['DESIGN', 'Design'], ['BUILDING', 'Build'],
  ['REVIEW', 'Review'], ['DEPLOYING', 'Deploy'], ['LIVE', 'Live'],
] as const

/** Where the project is in its life: requirements → design → build → review → deploy → live. */
function ProcessRail({ stage }: { stage?: Project['lifecycleStage'] }) {
  const current = Math.max(0, STAGES.findIndex(([id]) => id === stage))
  return (
    <ol className="pv-stages" aria-label="Project process">
      {STAGES.map(([id, label], index) => {
        const done = index < current || stage === 'LIVE'
        return (
          <li key={id} className={cx('pv-stage', id === stage && !done && 'is-current', done && 'is-done')} aria-current={id === stage ? 'step' : undefined}>
            <span>{done ? <AppIcon name="check" /> : index + 1}</span>
            <b>{label}</b>
          </li>
        )
      })}
    </ol>
  )
}

/** The requirements interview's answers. */
function RequirementSummary({ session }: { session: RequirementSession }) {
  const answered = session.questions.filter((q) => q.selectedOption || q.otherText).length
  return (
    <section className="pv-card pv-reqs">
      <header>
        <b>{session.status === 'APPROVED' ? 'Requirements approved' : 'Questions answered'}</b>
        <small>{answered} of {session.questions.length} answered</small>
      </header>
      <dl>
        {session.questions.map((q) => {
          const answer = q.otherText || q.options.find((o) => o.id === q.selectedOption)?.label
          return <div key={q.id}><dt>{q.prompt}</dt><dd className={answer ? undefined : 'is-waiting'}>{answer ?? 'Waiting for an answer'}</dd></div>
        })}
      </dl>
    </section>
  )
}

/** The current run: its status, the project stage, its numbers and every step. */
export function DetailsPanel({ run, steps, stage, requirements }: { run: AgentRun | undefined; steps: AgentStep[]; stage?: Project['lifecycleStage']; requirements: RequirementSession | null }) {
  if (!run && requirements) return <div className="pv-page"><ProcessRail stage={stage} /><RequirementSummary session={requirements} /></div>
  if (!run) return <EmptyState title="No build to show yet" hint="Start a build and its details appear here." />
  const status = runOutcome(run)
  return (
    <div className="pv-page">
      <header className="pv-head">
        <h3>{run.conversational ? 'Chat reply' : `${run.mode.charAt(0)}${run.mode.slice(1).toLowerCase()} run`}</h3>
        <span className={`pv-chip pv-chip--${status.tone}`}>{status.label}</span>
      </header>
      <ProcessRail stage={stage} />
      {requirements && <RequirementSummary session={requirements} />}
      <dl className="pv-stats">
        <div className="pv-stat"><dt>Started</dt><dd>{runWhen(run.createdAt)}</dd></div>
        <div className="pv-stat"><dt>Prompt tokens</dt><dd>{run.promptTokens.toLocaleString()}</dd></div>
        <div className="pv-stat"><dt>Completion tokens</dt><dd>{run.completionTokens.toLocaleString()}</dd></div>
        <div className="pv-stat"><dt>Restore point</dt><dd>{run.rollbackAvailable ? 'Available' : '—'}</dd></div>
      </dl>
      <section className="pv-well pv-steps">
        <h4>Steps</h4>
        {steps.length
          ? <div className="run-steps"><RunTimeline steps={steps} startedAt={run.createdAt} /></div>
          : <p className="pv-note">No steps recorded for this run.</p>}
      </section>
    </div>
  )
}

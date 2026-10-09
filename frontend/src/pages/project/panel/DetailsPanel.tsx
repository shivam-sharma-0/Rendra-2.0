import { type AgentRun, type AgentStep, type Project, type RequirementSession } from '../../../api/projects'
import { BuildStep } from '../chat/RunProgress'
import { RUN_STATUS_META, runWhen } from '../runUtils'

/* ── Build details (Figma 17) — the current run's metadata + full step timeline ────────────────── */
function RequirementSummary({ session }: { session: RequirementSession }) {
  const answered = session.questions.filter((question) => question.selectedOption || question.otherText).length
  return (
    <div className="pw-requirement-summary">
      <div className="pw-requirement-summary-head">
        <div><b>{session.status === 'APPROVED' ? 'Requirements approved' : 'Questions answered'}</b><small>{answered} of {session.questions.length} answered</small></div>
        <span className={'pw-summary-pulse' + (session.complete ? ' done' : '')} />
      </div>
      <div className="pw-requirement-summary-list">
        {session.questions.map((question) => {
          const answer = question.otherText || question.options.find((option) => option.id === question.selectedOption)?.label
          return <div className="pw-requirement-summary-row" key={question.id}><span>{question.prompt}</span><b>{answer ?? 'Waiting for answer'}</b></div>
        })}
      </div>
    </div>
  )
}

function ProcessRail({ stage }: { stage?: Project['lifecycleStage'] }) {
  const stages = [
    ['REQUIREMENTS', 'Requirements'], ['DESIGN', 'Design'], ['BUILDING', 'Build'],
    ['REVIEW', 'Review'], ['DEPLOYING', 'Deploy'], ['LIVE', 'Live'],
  ] as const
  const current = Math.max(0, stages.findIndex(([id]) => id === stage))
  return (
    <div className="pw-process-rail" aria-label="Project process">
      {stages.map(([id, label], index) => (
        <div key={id} className={'pw-process-rail-step' + (id === stage ? ' active' : '') + (index < current || stage === 'LIVE' ? ' complete' : '')}>
          <span>{index < current || stage === 'LIVE' ? 'x' : index + 1}</span>
          <b>{label}</b>
        </div>
      ))}
    </div>
  )
}

export function DetailsPanel({ run, steps, stage, requirements }: { run: AgentRun | undefined; steps: AgentStep[]; stage?: Project['lifecycleStage']; requirements: RequirementSession | null }) {
  if (!run && requirements) return <div className="pw-details"><ProcessRail stage={stage} /><RequirementSummary session={requirements} /></div>
  if (!run) return <div className="pw-emptyfiles">No build to show yet — start a build and its details appear here.</div>
  const meta = RUN_STATUS_META[run.status] ?? { label: run.status, color: '#6E6B74' }
  return (
    <div className="pw-details">
      <div className="pw-details-head">
        <b>{run.mode.toLowerCase()} run</b>
        <span className="pw-details-status" style={{ color: meta.color }}>{meta.label}</span>
      </div>
      <ProcessRail stage={stage} />
      {requirements && <RequirementSummary session={requirements} />}
      <dl className="pw-details-meta">
        <div><dt>Started</dt><dd>{runWhen(run.createdAt)}</dd></div>
        <div><dt>Prompt tokens</dt><dd>{run.promptTokens.toLocaleString()}</dd></div>
        <div><dt>Completion tokens</dt><dd>{run.completionTokens.toLocaleString()}</dd></div>
        <div><dt>Restore point</dt><dd>{run.rollbackAvailable ? 'Available' : '—'}</dd></div>
      </dl>
      <div className="pw-details-steps">
        {steps.length
          ? steps.map((step, i) => <BuildStep key={step.seq} step={step} previousCreatedAt={steps[i - 1]?.createdAt ?? run.createdAt} />)
          : <p className="pw-loading">No steps recorded for this run.</p>}
      </div>
    </div>
  )
}

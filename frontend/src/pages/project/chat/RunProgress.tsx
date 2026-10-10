import { useState, type MouseEvent } from 'react'
import { type AgentRun, type AgentStep } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { fileFromStep, friendlyToolTitle, reasoningFromStep, secondsBetween } from '../runUtils'
import { useTicker } from '../useTicker'
import { Markdown } from './Markdown'
import './run-steps.css'

/** The file-write checklist, embedded in the reply bubble in place of the plain thinking ticker —
 * shown only once the run has actually started writing files (see hasBuildActivity). */
export function BuildProgress({ run, steps }: { run: AgentRun; steps: AgentStep[] }) {
  return (
    <div className="msg-build">
      <b><span className="msg-build__orb" />Building…</b>
      <div className="run-steps">
        {steps.map((step, i) => (
          <BuildStep key={step.seq} step={step} previousCreatedAt={steps[i - 1]?.createdAt ?? run.createdAt} />
        ))}
        <ThinkingNow since={steps[steps.length - 1]?.createdAt ?? run.createdAt} />
      </div>
    </div>
  )
}

/** Live-ticking "Thinking for Ns…" line shown while the model hasn't produced its next step yet. */
function ThinkingNow({ since }: { since: string }) {
  useTicker(true)
  return <div className="run-step run-step--now"><span className="msg-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** The default pending state for a plain chat/explain reply: just a ticking "Thinking for Ns…" line,
 * no build checklist — that only appears once hasBuildActivity is true (see BuildProgress). */
export function ThinkingLine({ since }: { since: string }) {
  useTicker(true)
  return <div className="msg-thinking"><span className="msg-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** One step of a run (used in the chat's build card and the Details panel). */
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

import { useState } from 'react'
import { type AgentRun, type AgentStep } from '../../../api/projects'
import { Ico } from '../../../rendra/parts'
import { BRAIN_ICON, CHECK_ICON, COPY_ICON } from '../icons'
import { fileFromStep, friendlyToolTitle, reasoningFromStep, secondsBetween } from '../runUtils'
import { useTicker } from '../useTicker'
import { Markdown } from './Markdown'

/** The file-write checklist, embedded in the reply bubble in place of the plain thinking ticker —
 * shown only once the run has actually started writing files (see hasBuildActivity). */
export function BuildProgress({ run, steps }: { run: AgentRun; steps: AgentStep[] }) {
  return (
    <div className="msg-build">
      <b><span className="msg-build__orb" />Building…</b>
      <div className="pw-steps">
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
  return <div className="pw-step thinking"><span className="msg-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** The default pending state for a plain chat/explain reply: just a ticking "Thinking for Ns…" line,
 * no build checklist — that only appears once hasBuildActivity is true (see BuildProgress). */
export function ThinkingLine({ since }: { since: string }) {
  useTicker(true)
  return <div className="msg-thinking"><span className="msg-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

export function BuildStep({ step, previousCreatedAt }: { step: AgentStep; previousCreatedAt?: string }) {
  if (step.type === 'THINKING') return <ThinkingStep step={step} previousCreatedAt={previousCreatedAt} />
  const title = step.type === 'TOOL_CALL' ? friendlyToolTitle(step.content)
    : step.type === 'TOOL_RESULT' ? `${friendlyToolTitle(step.content)} complete`
      : step.type === 'STATUS' ? (step.content ?? 'Status')
        : step.type.replace('_', ' ')
  const file = fileFromStep(step)
  return (
    <div className="pw-step">
      <span className="pw-dot" />
      <span className="pw-steplabel">{title}</span>
      {file && <code>{file}</code>}
    </div>
  )
}

/** A "Thought for Ns" step expands in place to show the model's actual reasoning right inside
 * the Details panel without redirecting away — otherwise it expands to a short note instead of looking unresponsive. */
function ThinkingStep({ step, previousCreatedAt }: { step: AgentStep; previousCreatedAt?: string }) {
  const [expanded, setExpanded] = useState(false)
  const [copied, setCopied] = useState(false)
  const reasoning = reasoningFromStep(step)
  const seconds = secondsBetween(previousCreatedAt ?? step.createdAt, new Date(step.createdAt).getTime())

  const copyReasoning = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (!reasoning) return
    void navigator.clipboard.writeText(reasoning)
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  return (
    <div className="pw-thinking-step">
      <button
        type="button"
        className={`pw-thinking${expanded ? ' active' : ''}`}
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        title={expanded ? 'Collapse thinking' : 'Expand thinking'}
      >
        <Ico d={BRAIN_ICON} size={13} />
        <span>Thought for {seconds}s</span>
        <svg
          className={`pw-thinking-chev${expanded ? ' open' : ''}`}
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {expanded && (
        <div className="pw-thinking-inline">
          <div className="pw-thinking-inline-hd">
            <span className="pw-thinking-inline-title">Reasoning · {seconds}s</span>
            {reasoning && (
              <button
                type="button"
                className="pw-thinking-inline-copy"
                onClick={copyReasoning}
                title={copied ? 'Copied to clipboard' : 'Copy reasoning'}
              >
                <Ico d={copied ? CHECK_ICON : COPY_ICON} size={12} />
                <span>{copied ? 'Copied' : 'Copy'}</span>
              </button>
            )}
          </div>
          <div className="pw-thinking-inline-body">
            {reasoning ? (
              <Markdown content={reasoning} />
            ) : (
              <p className="pw-thinking-empty">No reasoning was streamed for this response.</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { type RequirementSession } from '../../../api/projects'

export function RequirementAnswerCard({ projectName, session, onPreview }: {
  projectName?: string
  session: RequirementSession
  onPreview: () => void
}) {
  const [expanded, setExpanded] = useState(true)
  return (
    <article className="pw-requirement-card">
      <header>
        <b>{projectName ? `Built ${projectName}` : 'Approved project requirements'}</b>
        <span aria-hidden="true">+</span>
      </header>
      {expanded && <div className="pw-requirement-card-body">
        {session.questions.map((question) => {
          const answer = question.otherText || question.options.find((option) => option.id === question.selectedOption)?.label || question.selectedOption
          return <div className="pw-requirement-card-row" key={question.id}><span>{question.prompt}</span><b>{answer ?? 'Not specified'}</b></div>
        })}
      </div>}
      <footer>
        <button type="button" onClick={() => setExpanded((value) => !value)}>{expanded ? 'Hide details' : 'Show details'}</button>
        <button type="button" onClick={onPreview}>Preview</button>
      </footer>
    </article>
  )
}

export function RequirementWaiting() {
  return (
    <div className="pw-requirement-waiting" role="status" aria-live="polite">
      <span className="pw-waiting-mark"><i /></span>
      <span>Waiting for answers</span>
      <span className="pw-waiting-dots" aria-hidden="true"><i /><i /><i /></span>
    </div>
  )
}

export function RequirementDialog({
  session, busy, error, onAnswer, onApprove, onSkipAll,
}: {
  session: RequirementSession
  busy: boolean
  error: string
  onAnswer: (questionId: string, selectedOption: string | null, otherText: string | null) => Promise<void>
  onApprove: () => Promise<void>
  onSkipAll: () => Promise<void>
}) {
  const firstIncomplete = session.questions.findIndex((question) =>
    question.required && !question.selectedOption && !question.otherText)
  const [index, setIndex] = useState(firstIncomplete >= 0 ? firstIncomplete : 0)
  const question = session.questions[index]
  const [selected, setSelected] = useState<string | null>(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((option) => option.recommended)?.id ?? null)
  const [other, setOther] = useState(question?.otherText ?? '')

  useEffect(() => {
    const next = session.questions.findIndex((item) => item.required && !item.selectedOption && !item.otherText)
    setIndex(next >= 0 ? next : Math.max(0, session.questions.length - 1))
  }, [session])

  useEffect(() => {
    setSelected(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((option) => option.recommended)?.id ?? null)
    setOther(question?.otherText ?? '')
  }, [question?.id, question?.selectedOption, question?.otherText, question?.recommendedOption, question?.options])

  if (!question) return null
  const last = index === session.questions.length - 1
  const isOther = selected === 'other'
  const answered = !!selected && (selected !== 'other' || !!other.trim())
  const visibleOptions = question.options.reduce<RequirementSession['questions'][number]['options']>((result, option) => {
    const custom = option.id === 'other' || /^(other|write your own|custom)/i.test(option.label.trim())
    if (custom) {
      if (!result.some((item) => item.id === 'other')) result.push({ ...option, id: 'other', label: 'Write your own...' })
    } else result.push(option)
    return result
  }, [])

  const choose = (optionId: string) => {
    setSelected(optionId)
    if (optionId !== 'other') setOther('')
  }

  const saveAndContinue = async () => {
    await onAnswer(question.id, isOther ? 'other' : selected, isOther ? other : null)
    if (last) await onApprove()
    else setIndex((value) => Math.min(value + 1, session.questions.length - 1))
  }

  return createPortal(
    <div className="pw-requirement-scrim">
      <div className="pw-requirement-modal" role="dialog" aria-modal="true" aria-label="Project requirements">
        <header className="pw-requirement-head">
          <h2>{question.prompt}</h2>
          <span className="pw-requirement-close" aria-hidden="true">x</span>
        </header>

        <div className="pw-requirement-progress" aria-label={`Question ${index + 1} of ${session.questions.length}`}>
          {session.questions.map((item, itemIndex) => (
            <span key={item.id} className={itemIndex <= index ? 'active' : ''} />
          ))}
        </div>

        <div className="pw-requirement-options">
          {visibleOptions.map((option) => option.id === 'other' ? (
            <div className={'pw-requirement-option-wrap' + (isOther ? ' selected' : '')} key={option.id}>
              <button type="button" className="pw-requirement-option pw-requirement-other-option"
                onClick={() => choose(option.id)} disabled={busy}>
                <span className="pw-requirement-radio">{isOther ? 'x' : ''}</span>
                <span><b>{option.label === 'Other' ? 'Write your own...' : option.label}</b></span>
              </button>
              {isOther && (
                <input className="pw-requirement-other-inline" value={other}
                  onChange={(event) => { setOther(event.target.value); setSelected('other') }}
                  onFocus={() => setSelected('other')} placeholder="Tell us what you have in mind"
                  aria-label="Your custom requirement" autoFocus disabled={busy} />
              )}
            </div>
          ) : (
            <button type="button" key={option.id}
              className={'pw-requirement-option' + (selected === option.id ? ' selected' : '')}
              onClick={() => choose(option.id)} disabled={busy}>
              <span className="pw-requirement-radio">{selected === option.id ? 'x' : ''}</span>
              <span className="pw-requirement-option-copy">
                <b>{option.label}</b>
                <small>{option.description}</small>
              </span>
              {option.recommended && <em>Recommended</em>}
            </button>
          ))}
        </div>

        {error && <p className="pw-error">{error}</p>}
        <footer className="pw-requirement-actions">
          <div className="pw-requirement-nav">
            <button type="button" className="pw-requirement-icon-button" aria-label="Previous question" title="Previous question"
              onClick={() => setIndex((value) => Math.max(0, value - 1))} disabled={index === 0 || busy}>‹</button>
            <button type="button" className="pw-requirement-icon-button" aria-label="Next question" title="Next question"
              onClick={() => setIndex((value) => Math.min(session.questions.length - 1, value + 1))} disabled={last || busy}>›</button>
          </div>
          <button type="button" className="pw-requirement-skip" onClick={() => void onSkipAll()} disabled={busy}>Skip all</button>
          <button type="button" className="pw-requirement-next" onClick={() => void saveAndContinue()} disabled={busy || !answered}>
            {busy ? 'Saving...' : last ? 'Submit' : 'Next'}
          </button>
        </footer>
      </div>
    </div>, document.body,
  )
}

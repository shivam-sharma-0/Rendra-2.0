import { useEffect, useState } from 'react'
import { type RequirementSession } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton, NeuDialog, NeuIconButton } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import './dialogs.css'

type Question = RequirementSession['questions'][number]

const answerOf = (q: Question) => q.otherText || q.options.find((o) => o.id === q.selectedOption)?.label || q.selectedOption

/** In the chat once the interview is approved: the answers, collapsible, and a way to the preview. */
export function RequirementAnswerCard({ session, onPreview }: { projectName?: string; session: RequirementSession; onPreview: () => void }) {
  const [expanded, setExpanded] = useState(true)
  return (
    <article className="rq-card">
      <header>
        <span className="rq-card__mark" aria-hidden><AppIcon name="check" /></span>
        <b>Requirements approved</b>
        <small>{session.questions.length} answers</small>
      </header>
      {expanded && (
        <dl>
          {session.questions.map((q) => <div key={q.id}><dt>{q.prompt}</dt><dd>{answerOf(q) ?? 'Not specified'}</dd></div>)}
        </dl>
      )}
      <footer>
        <NeuButton size="sm" onClick={() => setExpanded((v) => !v)} aria-expanded={expanded}>{expanded ? 'Hide answers' : 'Show answers'}</NeuButton>
        <NeuButton size="sm" variant="primary" onClick={onPreview}>Preview</NeuButton>
      </footer>
    </article>
  )
}

/** In the chat while the interview is open. */
export function RequirementWaiting() {
  return (
    <div className="rq-waiting" role="status" aria-live="polite">
      <span className="msg-spin" />
      Waiting for your answers
    </div>
  )
}

/** Options as shown: any "other" / "write your own" option becomes one free-text choice at the end. */
function visibleOptions(question: Question) {
  return question.options.reduce<Question['options']>((result, option) => {
    const custom = option.id === 'other' || /^(other|write your own|custom)/i.test(option.label.trim())
    if (custom) {
      if (!result.some((item) => item.id === 'other')) result.push({ ...option, id: 'other', label: 'Write your own…' })
    } else result.push(option)
    return result
  }, [])
}

const preferred = (q?: Question) => q?.selectedOption ?? q?.recommendedOption ?? q?.options.find((o) => o.recommended)?.id ?? null

/**
 * The requirements interview, one question at a time. It cannot be dismissed: answer each question
 * (the recommended option is preselected) or use "Skip all" to accept the recommendations.
 */
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
  const firstIncomplete = session.questions.findIndex((q) => q.required && !q.selectedOption && !q.otherText)
  const [index, setIndex] = useState(firstIncomplete >= 0 ? firstIncomplete : 0)
  const question = session.questions[index]
  const [selected, setSelected] = useState<string | null>(preferred(question))
  const [other, setOther] = useState(question?.otherText ?? '')

  useEffect(() => {
    const next = session.questions.findIndex((q) => q.required && !q.selectedOption && !q.otherText)
    setIndex(next >= 0 ? next : Math.max(0, session.questions.length - 1))
  }, [session])

  useEffect(() => {
    setSelected(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((o) => o.recommended)?.id ?? null)
    setOther(question?.otherText ?? '')
  }, [question?.id, question?.selectedOption, question?.otherText, question?.recommendedOption, question?.options])

  if (!question) return null
  const total = session.questions.length
  const last = index === total - 1
  const isOther = selected === 'other'
  const answered = !!selected && (selected !== 'other' || !!other.trim())

  const choose = (optionId: string) => {
    setSelected(optionId)
    if (optionId !== 'other') setOther('')
  }
  const saveAndContinue = async () => {
    await onAnswer(question.id, isOther ? 'other' : selected, isOther ? other : null)
    if (last) await onApprove()
    else setIndex((value) => Math.min(value + 1, total - 1))
  }

  return (
    <NeuDialog
      locked
      size="lg"
      className="rq-dialog"
      title={question.prompt}
      description={`Question ${index + 1} of ${total} — your answers shape what Rendra builds.`}
      onClose={() => {}}
      footer={(
        <>
          <NeuIconButton aria-label="Previous question" title="Previous question" onClick={() => setIndex((v) => Math.max(0, v - 1))} disabled={index === 0 || busy}>
            <AppIcon name="back" />
          </NeuIconButton>
          <NeuIconButton aria-label="Next question" title="Next question" onClick={() => setIndex((v) => Math.min(total - 1, v + 1))} disabled={last || busy}>
            <AppIcon name="caretRight" />
          </NeuIconButton>
          <span className="rq-grow" />
          <NeuButton onClick={() => void onSkipAll()} disabled={busy}>Skip all</NeuButton>
          <NeuButton variant="accent" onClick={() => void saveAndContinue()} disabled={busy || !answered}>{busy ? 'Saving…' : last ? 'Submit' : 'Next'}</NeuButton>
        </>
      )}
    >
      <ol className="rq-progress" aria-label={`Question ${index + 1} of ${total}`}>
        {session.questions.map((q, i) => <li key={q.id} className={cx(i < index && 'is-done', i === index && 'is-current')} />)}
      </ol>

      <div className="rq-options" role="radiogroup" aria-label={question.prompt}>
        {visibleOptions(question).map((option) => {
          const checked = selected === option.id
          return (
            <div key={option.id} className={cx('rq-option', checked && 'is-checked')}>
              <button type="button" role="radio" aria-checked={checked} onClick={() => choose(option.id)} disabled={busy}>
                <span className="rq-radio" aria-hidden />
                <span className="rq-option__text">
                  <b>{option.label}</b>
                  {option.id !== 'other' && option.description && <small>{option.description}</small>}
                </span>
                {option.recommended && <em>Recommended</em>}
              </button>
              {option.id === 'other' && checked && (
                <input className="neu-input rq-other" value={other} placeholder="Tell us what you have in mind" aria-label="Your own answer"
                  onChange={(e) => setOther(e.target.value)} autoFocus disabled={busy} />
              )}
            </div>
          )
        })}
      </div>
      {error && <NeuAlert>{error}</NeuAlert>}
    </NeuDialog>
  )
}

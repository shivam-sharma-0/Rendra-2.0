import { useEffect, useState } from 'react'
import { type RequirementSession } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton, NeuDialog, NeuIconButton } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import { hasUnansweredSetup, interviewQuestions } from '../../../lib/requirements'
import './dialogs.css'

type Question = RequirementSession['questions'][number]

const answerOf = (q: Question) => q.otherText || q.options.find((o) => o.id === q.selectedOption)?.label || q.selectedOption

/** In the chat once the interview is approved: the answers, collapsible, and a way to the preview. */
export function RequirementAnswerCard({ session, onPreview }: { projectName?: string; session: RequirementSession; onPreview: () => void }) {
  const [expanded, setExpanded] = useState(true)
  const questions = interviewQuestions(session)
  return (
    <article className="rq-card">
      <header>
        <span className="rq-card__mark" aria-hidden><AppIcon name="check" /></span>
        <b>{session.artifactStatus === 'PENDING' ? 'Requirements approved; preparing build' : 'Requirements approved'}</b>
        <small>{questions.length ? `${questions.length} answers` : session.review ? 'App plan ready' : 'Request approved'}</small>
      </header>
      {expanded && (
        <dl>
          {questions.map((q) => <div key={q.id}><dt>{q.prompt}</dt><dd>{answerOf(q) ?? 'Not specified'}</dd></div>)}
          {session.review?.goal && <div><dt>Application</dt><dd>{session.review.goal}</dd></div>}
          {!!session.review?.pages.length && <div><dt>Pages</dt><dd>{session.review.pages.join(', ')}</dd></div>}
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
const customOption = (option: Question['options'][number]) => option.id === 'other' || /^(other|write your own|custom)/i.test(option.label.trim())
function visibleOptions(question: Question) {
  return question.options.reduce<Question['options']>((result, option) => {
    const custom = customOption(option)
    if (custom) {
      if (!result.some(customOption)) result.push({ ...option, label: 'Write your own…' })
    } else result.push(option)
    return result
  }, [])
}

const preferred = (q?: Question) => q?.selectedOption ?? (q?.key.startsWith('platform_') ? null : q?.recommendedOption ?? q?.options.find((o) => o.recommended)?.id ?? null)

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
  const questions = interviewQuestions(session)
  const firstIncomplete = questions.findIndex((q) => q.required && !q.selectedOption && !q.otherText)
  const [index, setIndex] = useState(firstIncomplete >= 0 ? firstIncomplete : 0)
  const question = questions[index]
  const [selected, setSelected] = useState<string | null>(preferred(question))
  const [other, setOther] = useState(question?.otherText ?? '')

  useEffect(() => {
    const next = questions.findIndex((q) => q.required && !q.selectedOption && !q.otherText)
    setIndex(next >= 0 ? next : Math.max(0, questions.length - 1))
  }, [session])

  useEffect(() => {
    setSelected(preferred(question))
    setOther(question?.otherText ?? '')
  }, [question?.id, question?.selectedOption, question?.otherText, question?.recommendedOption, question?.options])

  if (!question && !session.complete) return <div role="status" aria-live="polite">Preparing requirement questions...</div>
  if (!question) return (
    <NeuDialog locked size="lg" className="rq-dialog" title={session.review ? 'Your application plan' : 'Review your request'} onClose={() => {}}
      description={session.review ? 'The standard setup is handled. Review what Rendra will build.' : 'A detailed plan was unavailable. Your original request is preserved for the build.'}
      footer={<NeuButton variant="accent" onClick={() => void onApprove()} disabled={busy || session.processing || session.status !== 'APPROVAL_REQUIRED'}>{busy ? 'Saving…' : 'Approve and build'}</NeuButton>}>
      {session.review?.goal && <p>{session.review.goal}</p>}
      {!!session.review?.pages.length && <p>Pages: {session.review.pages.join(', ')}</p>}
      {session.review?.features.length ? <ul>{session.review.features.map((feature) => <li key={feature.id}>{feature.description || feature.id}</li>)}</ul>
        : <p>The build will follow your request, including its required pages and working features.</p>}
      {error && <NeuAlert>{error}</NeuAlert>}
    </NeuDialog>
  )
  const total = questions.length
  const last = index === total - 1
  const isOther = question.options.some((option) => option.id === selected && customOption(option))
  const answered = !!selected && (!isOther || !!other.trim())
  const unchanged = selected === question.selectedOption && (isOther ? other.trim() || null : null) === (question.otherText?.trim() || null)
  const readyToApprove = session.complete && session.status === 'APPROVAL_REQUIRED' && !session.processing && unchanged

  const choose = (optionId: string) => {
    setSelected(optionId)
    if (!question.options.some((option) => option.id === optionId && customOption(option))) setOther('')
  }
  const saveAndContinue = async () => {
    await onAnswer(question.id, selected, isOther ? other : null)
    if (!last) setIndex((value) => Math.min(value + 1, total - 1))
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
          <NeuButton onClick={() => void onSkipAll()} disabled={busy || hasUnansweredSetup(session)}>Skip all</NeuButton>
          <NeuButton variant="accent" onClick={() => void (readyToApprove ? onApprove() : saveAndContinue())} disabled={busy || session.processing || !answered}>{busy ? 'Saving…' : session.processing ? 'Reviewing...' : readyToApprove ? 'Approve requirements' : last ? 'Review answers' : 'Next'}</NeuButton>
        </>
      )}
    >
      <ol className="rq-progress" aria-label={`Question ${index + 1} of ${total}`}>
        {questions.map((q, i) => <li key={q.id} className={cx(i < index && 'is-done', i === index && 'is-current')} />)}
      </ol>
      {readyToApprove && <dl>{questions.map((q) => <div key={q.id}><dt>{q.prompt}</dt><dd>{answerOf(q) ?? 'Not specified'}</dd></div>)}</dl>}
      {readyToApprove && session.review && (
        <section aria-label="Planned application">
          <h3>What will be built</h3>
          {session.review.goal && <p>{session.review.goal}</p>}
          <ul>{session.review.features.map((feature, position) => <li key={`${feature.id}-${position}`}>{feature.description || feature.id}</li>)}</ul>
          {session.review.answersChanged && <p>Your saved answers take priority over this earlier draft.</p>}
          {session.review.features.some((feature) => !feature.checkPlanned) && <p>Some features still need verification during the build. They remain part of your request.</p>}
        </section>
      )}
      {session.reviewStatus === 'FALLBACK' && <p role="status">Automatic review was unavailable. Check these answers before approving.</p>}

      <div className="rq-options" role="radiogroup" aria-label={question.prompt}>
        {visibleOptions(question).map((option) => {
          const checked = selected === option.id
          return (
            <div key={option.id} className={cx('rq-option', checked && 'is-checked')}>
              <button type="button" role="radio" aria-checked={checked} onClick={() => choose(option.id)} disabled={busy}>
                <span className="rq-radio" aria-hidden />
                <span className="rq-option__text">
                  <b>{option.label}</b>
                  {!customOption(option) && option.description && <small>{option.description}</small>}
                </span>
                {option.recommended && <em>Recommended</em>}
              </button>
              {customOption(option) && checked && (
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

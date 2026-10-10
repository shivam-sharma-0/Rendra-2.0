import { Link } from 'react-router-dom'
import { AppIcon } from '../../../components/icons'
import { cx } from '../../../lib/cx'
import { FACE_COLORS, STEPS } from './content'
import type { OnboardingState } from './useOnboarding'

/** Left side: the brand, where you are in the three steps, and a live preview of the workspace. */
export function StepsRail({ state }: { state: OnboardingState }) {
  const { step, workspaceName, slug, color, initials, filledInvites, kind } = state
  const shownName = workspaceName.trim() || 'Your workspace'
  const people = step === 3
    ? filledInvites.length ? `You + ${filledInvites.length} teammate${filledInvites.length > 1 ? 's' : ''}` : 'Just you for now'
    : kind === 'solo' ? 'Just you' : 'You · invite teammates next'

  return (
    <aside className="ob-rail neu-panel">
      <Link to="/" className="ob-brand"><span className="ob-mark" aria-hidden><i /></span><b>Rendra</b></Link>

      <ol className="ob-steps">
        {STEPS.map((s, i) => {
          const n = i + 1
          const done = n < step
          const current = n === step
          return (
            <li key={s.title} className={cx('ob-step', done && 'is-done', current && 'is-current')} aria-current={current ? 'step' : undefined}>
              <span className="ob-step__mark">{done ? <AppIcon name="check" /> : n}</span>
              <span className="ob-step__text"><b>{s.title}</b><small>{s.sub}</small></span>
            </li>
          )
        })}
      </ol>

      <section className="ob-preview" aria-label="Workspace preview">
        <span className="ob-preview__label">Preview</span>
        <div className="ob-preview__ws">
          <span className="ob-badge" style={{ background: color }}>{shownName.charAt(0).toUpperCase()}</span>
          <span><b>{shownName}</b><small>rendra.app/{slug || 'workspace'}</small></span>
        </div>
        <div className="ob-preview__people">
          <span className="ob-faces">
            <span className="ob-face ob-face--you">{initials}</span>
            {step === 3 && filledInvites.map((invite, i) => (
              <span key={i} className="ob-face" style={{ background: FACE_COLORS[i % FACE_COLORS.length] }}>{invite.email.slice(0, 2).toUpperCase()}</span>
            ))}
          </span>
          <small>{people}</small>
        </div>
      </section>
    </aside>
  )
}

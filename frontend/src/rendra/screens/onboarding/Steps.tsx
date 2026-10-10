import { AppIcon } from '../../../components/icons'
import { NeuSelect, NeuTextField } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import { COLORS, GOALS, INVITE_ROLES, KINDS, ROLES, type InviteRole } from './content'
import type { OnboardingState } from './useOnboarding'

/** Step 1: role and what to build first. */
export function AboutYouStep({ state }: { state: OnboardingState }) {
  return (
    <>
      <header className="ob-head">
        <h1>Nice to meet you, <em>{state.firstName}</em>.</h1>
        <p>Two quick questions so Rendra can tailor templates and suggestions.</p>
      </header>
      <fieldset className="ob-field">
        <legend>What best describes you?</legend>
        <div className="ob-roles">
          {ROLES.map((r) => (
            <button type="button" key={r.id} aria-pressed={state.role === r.id} className={cx('ob-tile', state.role === r.id && 'is-on')} onClick={() => state.setRole(r.id)}>
              <AppIcon name={r.icon} size={20} />
              <span>{r.label}</span>
            </button>
          ))}
        </div>
      </fieldset>
      <fieldset className="ob-field">
        <legend>What do you want to build first? <small>Pick any</small></legend>
        <div className="ob-goals">
          {GOALS.map((g) => {
            const on = state.goals.includes(g.id)
            return (
              <button type="button" key={g.id} aria-pressed={on} className={cx('ob-chip', on && 'is-on')} onClick={() => state.toggleGoal(g.id)}>
                {on && <AppIcon name="check" />}{g.label}
              </button>
            )
          })}
        </div>
      </fieldset>
    </>
  )
}

/** Step 2: the workspace's name, address, audience and colour. */
export function WorkspaceStep({ state }: { state: OnboardingState }) {
  return (
    <>
      <header className="ob-head">
        <h1>Create your <em>workspace</em></h1>
        <p>A workspace holds your projects, teammates, connectors and credits.</p>
      </header>
      <div className="ob-two">
        <NeuTextField label="Workspace name" value={state.workspaceName} onChange={(e) => state.renameWorkspace(e.target.value)} error={state.nameError || undefined} />
        <div className="neu-field">
          <label htmlFor="ob-slug" className="neu-field__label">Workspace URL</label>
          <div className="ob-slug">
            <span>rendra.app/</span>
            <input id="ob-slug" value={state.slug} onChange={(e) => state.setSlug(e.target.value)} />
          </div>
        </div>
      </div>
      <fieldset className="ob-field">
        <legend>Who is it for?</legend>
        <div className="ob-kinds">
          {KINDS.map((k) => (
            <button type="button" key={k.id} aria-pressed={state.kind === k.id} className={cx('ob-tile ob-tile--wide', state.kind === k.id && 'is-on')} onClick={() => state.setKind(k.id)}>
              <span className="ob-tile__icon"><AppIcon name={k.icon} size={20} /></span>
              <b>{k.label}</b>
              <small>{k.desc}</small>
            </button>
          ))}
        </div>
      </fieldset>
      <fieldset className="ob-field ob-field--row">
        <legend>Colour</legend>
        <div className="ob-colors" role="radiogroup" aria-label="Workspace colour">
          {COLORS.map((c) => (
            <button type="button" key={c.value} role="radio" aria-checked={state.color === c.value} aria-label={c.name} title={c.name}
              className={cx('ob-color', state.color === c.value && 'is-on')} style={{ background: c.value }} onClick={() => state.setColor(c.value)} />
          ))}
        </div>
      </fieldset>
    </>
  )
}

/** Step 3: teammates to invite (sent on finish). */
export function InviteStep({ state }: { state: OnboardingState }) {
  return (
    <>
      <header className="ob-head">
        <h1>Build <em>together</em></h1>
        <p>Teammates share projects, one chat per project and the workspace credits. You can invite more later.</p>
      </header>
      <div className="ob-invites">
        {state.invites.map((invite, i) => (
          <div className="ob-invite" key={i}>
            <input className="neu-input" type="email" placeholder="teammate@company.com" aria-label={`Teammate email ${i + 1}`}
              value={invite.email} onChange={(e) => state.updateInvite(i, { email: e.target.value })} />
            <NeuSelect aria-label={`Role for teammate ${i + 1}`} value={invite.role} onChange={(e) => state.updateInvite(i, { role: e.target.value as InviteRole })}>
              {INVITE_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </NeuSelect>
          </div>
        ))}
        <button type="button" className="ob-add" onClick={state.addInvite}><AppIcon name="plus" />Add another</button>
      </div>
    </>
  )
}

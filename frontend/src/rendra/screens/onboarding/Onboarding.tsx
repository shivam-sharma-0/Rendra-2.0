import { AppIcon } from '../../../components/icons'
import { NeuButton } from '../../../components/neu'
import { AboutYouStep, InviteStep, WorkspaceStep } from './Steps'
import { StepsRail } from './StepsRail'
import { useOnboarding } from './useOnboarding'
import './onboarding.css'

/** First run after sign-up: about you → create the workspace → invite teammates. */
export default function Onboarding() {
  const state = useOnboarding()
  const { step, busy, filledInvites } = state

  return (
    <div className="ob">
      <StepsRail state={state} />
      <main className="ob-main">
        <div className="ob-progress" aria-label={`Step ${step} of 3`}>
          <span>Step {step} of 3</span>
          <div className="ob-progress__track"><i style={{ width: `${(step / 3) * 100}%` }} /></div>
        </div>

        <div className="ob-body" key={step}>
          {step === 1 && <AboutYouStep state={state} />}
          {step === 2 && <WorkspaceStep state={state} />}
          {step === 3 && <InviteStep state={state} />}
        </div>

        <footer className="ob-foot">
          {step > 1 && <NeuButton size="lg" onClick={state.back} disabled={busy}><AppIcon name="back" />Back</NeuButton>}
          <span className="ob-grow" />
          {step === 3 ? (
            <>
              <NeuButton size="lg" className="ob-skip" onClick={state.skip} disabled={busy}>Skip for now</NeuButton>
              <NeuButton size="lg" variant="primary" onClick={() => void state.finish()} disabled={busy}>
                {busy ? 'Finishing…' : filledInvites.length ? `Send ${filledInvites.length} invite${filledInvites.length > 1 ? 's' : ''} & start` : 'Finish setup'}
                <AppIcon name="arrowRight" />
              </NeuButton>
            </>
          ) : (
            <NeuButton size="lg" variant="primary" onClick={() => void state.next()} disabled={busy}>
              {busy ? 'Please wait…' : 'Continue'}<AppIcon name="arrowRight" />
            </NeuButton>
          )}
        </footer>
      </main>
    </div>
  )
}

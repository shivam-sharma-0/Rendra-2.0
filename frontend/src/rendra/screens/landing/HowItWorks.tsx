import type { ComponentType } from 'react'
import { NeuPanel } from '../../../components/neu'
import { DescribeIllustration, RefineIllustration, ShipIllustration } from './StepIllustrations'

const STEPS: readonly { kicker: string; title: string; text: string; Illustration: ComponentType }[] = [
  { kicker: '01 — DESCRIBE', title: 'Say what you want', text: 'Type, talk or drop a screenshot. Plan mode maps out pages and data first.', Illustration: DescribeIllustration },
  { kicker: '02 — REFINE', title: 'Point, click, tweak', text: 'Select any element in the preview and ask for a change — or edit the code yourself.', Illustration: RefineIllustration },
  { kicker: '03 — SHIP', title: 'Go live in one click', text: 'Hosting, SSL and a shareable URL are built in. Your code is always yours.', Illustration: ShipIllustration },
]

/** Three-step explainer. */
export default function HowItWorks() {
  return (
    <section id="how" className="lp-section">
      <header className="lp-section__head">
        <h2 className="lp-display lp-h2">From idea to live app in <em>three</em> moves.</h2>
        <p>No setup, no boilerplate. You stay in the conversation — Rendra writes, runs and ships the code.</p>
      </header>
      <div className="lp-steps">
        {STEPS.map(({ kicker, title, text, Illustration }) => (
          <NeuPanel as="article" key={kicker} interactive className="lp-step">
            <Illustration />
            <div className="lp-step__copy">
              <span className="lp-step__kicker">{kicker}</span>
              <h3>{title}</h3>
              <p>{text}</p>
            </div>
          </NeuPanel>
        ))}
      </div>
    </section>
  )
}

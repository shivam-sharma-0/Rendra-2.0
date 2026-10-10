import { AppIcon } from '../../../components/icons'
import { NeuButton } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import LandingFooter from '../landing/LandingFooter'
import LandingNav from '../landing/LandingNav'
import { useStartProject } from '../landing/useStartProject'
import '../landing/landing.css'
import './pricing.css'

type Plan = { name: string; blurb: string; features: string[]; available: boolean }

/** Free is the only plan today; Pro and Team are shown as planned, without prices or sign-up. */
const PLANS: Plan[] = [
  {
    name: 'Free',
    blurb: 'For exploring your next big idea.',
    available: true,
    features: ['Unlimited projects', 'A daily AI allowance', 'Live preview, code and version history', 'Shared workspace with teammates'],
  },
  {
    name: 'Pro',
    blurb: 'For individuals building serious products.',
    available: false,
    features: ['Everything in Free', 'A larger daily AI allowance', 'Custom domains', 'Priority builds'],
  },
  {
    name: 'Team',
    blurb: 'For teams making things together.',
    available: false,
    features: ['Everything in Pro', 'Pooled AI allowance', 'Roles and access controls', 'Central billing'],
  },
]

/** Public plans page at /pricing. */
export default function Pricing() {
  const { goToStart, isAuthenticated } = useStartProject()
  return (
    <div className="lp">
      <div className="lp-inner">
        <LandingNav onStart={goToStart} />
        <main className="pr">
          <header className="pr-head">
            <small>Plans</small>
            <h1>Build freely. <em>Grow when you need to.</em></h1>
            <p>Rendra is free while we build out paid plans. Pro and Team are on the way.</p>
          </header>
          <div className="pr-cards">
            {PLANS.map((plan) => (
              <article key={plan.name} className={cx('pr-card', plan.available ? 'neu-panel' : 'pr-card--soon')}>
                <div className="pr-card__top">
                  <h2>{plan.name}</h2>
                  {plan.available ? <span className="pr-tag pr-tag--on">Available now</span> : <span className="pr-tag">Coming soon</span>}
                </div>
                <p>{plan.blurb}</p>
                <div className="pr-price">{plan.available ? <><b>$0</b><small>forever</small></> : <small>Pricing to be announced</small>}</div>
                {plan.available
                  ? <NeuButton variant="primary" block onClick={goToStart}>{isAuthenticated ? 'Go to dashboard' : 'Start building'}<AppIcon name="arrowRight" /></NeuButton>
                  : <NeuButton block disabled>Coming soon</NeuButton>}
                <ul>
                  {plan.features.map((f) => <li key={f}><AppIcon name="check" />{f}</li>)}
                </ul>
              </article>
            ))}
          </div>
        </main>
        <LandingFooter />
      </div>
    </div>
  )
}

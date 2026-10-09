import { NeuPanel } from '../../../components/neu'

/** Decorative cards either side of the hero (hidden on narrow screens). */
export default function FloatingCards() {
  return (
    <div aria-hidden>
      <NeuPanel size="sm" className="lp-float lp-float--left">
        <small>Edited</small>
        <code className="lp-file lp-file--violet">Hero.tsx</code>
        <code className="lp-file lp-file--orange">auth.ts</code>
      </NeuPanel>
      <NeuPanel size="sm" className="lp-float lp-float--right">
        <span className="lp-live-dot" />
        <span className="lp-float__stack">
          <b>Deployed</b>
          <code>yourapp.rendra.app</code>
        </span>
      </NeuPanel>
    </div>
  )
}

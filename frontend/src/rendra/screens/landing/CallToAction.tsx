import { NeuButton, NeuPanel } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import BrandMark from './BrandMark'

/** Closing call to action. */
export default function CallToAction({ onStart }: { onStart: () => void }) {
  return (
    <NeuPanel as="section" className="lp-cta">
      <BrandMark className="lp-cta__mark" />
      <div className="lp-cta__copy">
        <h2 className="lp-display lp-h2">Your next app is<br />one <em>sentence</em> away.</h2>
        <p>Free to start. No credit card.</p>
      </div>
      <NeuButton variant="primary" size="xl" pill onClick={onStart}>Start building free <Ico d={ICON.arrowRight} size={16} sw={2.2} /></NeuButton>
    </NeuPanel>
  )
}

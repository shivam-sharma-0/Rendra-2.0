import { ICON } from '../../icons'
import { Ico } from '../../parts'
import BrandMark from './BrandMark'

/* Small, decorative mock-ups for the "How it works" cards. */

export function DescribeIllustration() {
  return (
    <div className="lp-ill lp-ill--describe neu-well neu-well--deep" aria-hidden>
      <div className="lp-ill__bubble">A booking app for my yoga studio</div>
      <div className="lp-ill__reply"><BrandMark className="lp-ill__mark" /><span className="lp-ill__line lp-ill__line--short" /></div>
      <span className="lp-ill__line lp-ill__line--long" />
    </div>
  )
}

export function RefineIllustration() {
  return (
    <div className="lp-ill lp-ill--refine neu-well neu-well--deep" aria-hidden>
      <span className="lp-ill__heading" />
      <div className="lp-ill__cards">
        <div className="lp-ill__card lp-ill__card--selected"><span>Card</span></div>
        <div className="lp-ill__card" />
      </div>
      <span className="lp-ill__cta" />
      <svg className="lp-ill__cursor" width="22" height="22" viewBox="0 0 24 24"><path d="M4 3l15 7-6.5 2L10 19z" /></svg>
    </div>
  )
}

export function ShipIllustration() {
  return (
    <div className="lp-ill lp-ill--ship" aria-hidden>
      <div className="lp-ill__url">
        <Ico d={ICON.lock} size={14} sw={2.2} />
        <code>yogaflow.rendra.app</code>
        <span className="lp-ill__live"><span className="lp-live-dot" />Live</span>
      </div>
      <div className="lp-ill__tags"><span>Custom domain</span><span>GitHub sync</span></div>
    </div>
  )
}

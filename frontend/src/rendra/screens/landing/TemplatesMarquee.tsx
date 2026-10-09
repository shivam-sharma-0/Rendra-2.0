import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { NeuPanel } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { TEMPLATE_PREVIEWS, type TemplatePreview } from './content'

/** Auto-scrolling row of template cards; pauses on hover and under reduced motion. */
export default function TemplatesMarquee() {
  return (
    <section id="templates" className="lp-section lp-section--bleed">
      <header className="lp-section__head lp-section__head--center">
        <h2 className="lp-display lp-h2 lp-h2--sm">Start from something <em>beautiful</em></h2>
        <Link to="/templates" className="lp-more">Browse templates <Ico d={ICON.arrowRight} size={15} sw={2.2} /></Link>
      </header>
      <div className="lp-marquee">
        <div className="lp-marquee__track">
          {/* The list is rendered twice so the loop is seamless; the copy is hidden from screen readers. */}
          {[false, true].map((copy) => TEMPLATE_PREVIEWS.map((t) => <TemplateCard key={`${t.name}-${copy}`} template={t} hidden={copy} />))}
        </div>
      </div>
    </section>
  )
}

function TemplateCard({ template: t, hidden }: { template: TemplatePreview; hidden: boolean }) {
  const colours = { '--tpl-bg': t.bg, '--tpl-fg': t.fg, '--tpl-accent': t.accent, '--tpl-card': t.card } as CSSProperties
  return (
    <NeuPanel size="sm" interactive className="lp-template" aria-hidden={hidden || undefined}>
      <div className="lp-template__well neu-well neu-well--deep">
        <div className="lp-template__preview" style={colours}>
          <div className="lp-template__top"><span /><span /></div>
          <span className="lp-template__title" />
          <span className="lp-template__sub" />
          <div className="lp-template__cards"><span /><span /><span /></div>
        </div>
      </div>
      <div className="lp-template__meta"><b>{t.name}</b><small>{t.kind}</small></div>
    </NeuPanel>
  )
}

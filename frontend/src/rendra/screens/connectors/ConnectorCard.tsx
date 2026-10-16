import BrandLogo from '../../../components/brand/BrandLogo'
import { NeuPanel } from '../../../components/neu'
import type { Connector } from './content'

type Props = { connector: Connector }

/** One planned integration; it cannot be connected until its backend exists. */
export default function ConnectorCard({ connector: c }: Props) {
  return (
    <NeuPanel as="article" size="sm" className="cn-card" aria-label={c.name}>
      <div className="cn-card__top">
        <span className="cn-card__logo" aria-hidden><BrandLogo brand={c.brand} size={22} /></span>
        <span className="cn-card__text"><b>{c.name}</b><small>{c.category}</small></span>
        <span className="cn-card__toggle" aria-label={`${c.name} is coming soon`}>Coming soon</span>
      </div>
      <p>{c.desc}</p>
    </NeuPanel>
  )
}

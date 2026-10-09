import BrandLogo from '../../../components/brand/BrandLogo'
import { NeuButton, NeuPanel } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import type { Connector } from './content'

type Props = { connector: Connector; connected: boolean; onToggle: () => void }

/** One integration with its connect / disconnect toggle. */
export default function ConnectorCard({ connector: c, connected, onToggle }: Props) {
  return (
    <NeuPanel as="article" size="sm" className="cn-card" aria-label={c.name}>
      <div className="cn-card__top">
        <span className="cn-card__logo" aria-hidden><BrandLogo brand={c.brand} size={22} /></span>
        <span className="cn-card__text"><b>{c.name}</b><small>{c.category}</small></span>
        <NeuButton size="sm" pill aria-pressed={connected} className={cx('cn-card__toggle', connected && 'is-on')} onClick={onToggle}
          aria-label={`${connected ? 'Disconnect' : 'Connect'} ${c.name}`}>
          {connected && <Ico d={ICON.check} size={13} sw={2.4} />}{connected ? 'Connected' : 'Connect'}
        </NeuButton>
      </div>
      <p>{c.desc}</p>
    </NeuPanel>
  )
}

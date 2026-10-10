import { Link } from 'react-router-dom'
import { AppIcon } from '../../../components/icons'
import { NeuButton, neuButtonClass } from '../../../components/neu'
import BrandMark from './BrandMark'

const LINKS = [
  { label: 'Product', href: '/#how' },
  { label: 'Templates', to: '/templates' },
  { label: 'Pricing', to: '/pricing' },
  { label: 'Docs', href: '#docs' },
  { label: 'Community', href: '#community' },
] as const

/** Top bar: brand, section links (hidden on small screens), and compact log in / start buttons. */
export default function LandingNav({ onStart }: { onStart: () => void }) {
  return (
    <nav className="lp-nav" aria-label="Main">
      <Link to="/" className="lp-brand"><BrandMark className="lp-brand__mark" />Rendra</Link>
      <div className="lp-nav__links">
        {LINKS.map((l) => ('to' in l
          ? <Link key={l.label} to={l.to}>{l.label}</Link>
          : <a key={l.label} href={l.href}>{l.label}</a>))}
      </div>
      <div className="lp-nav__actions">
        <Link to="/auth" className={neuButtonClass({ size: 'sm', pill: true })}>Log in</Link>
        <NeuButton variant="primary" size="sm" pill onClick={onStart}>Start building <AppIcon name="arrowRight" /></NeuButton>
      </div>
    </nav>
  )
}

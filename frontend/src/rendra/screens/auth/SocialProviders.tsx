import { clearLoggedOutMarker, googleLoginUrl } from '../../../auth/api'
import BrandLogo from '../../../components/brand/BrandLogo'
import type { BrandId } from '../../../components/brand/brands'
import { NeuButton } from '../../../components/neu'

type Provider = {
  id: Extract<BrandId, 'google' | 'github' | 'apple'>
  name: string
  /** OAuth start URL; providers without one are shown as "Soon". */
  href?: () => string
  lastUsed?: boolean
}

const PROVIDERS: readonly Provider[] = [
  { id: 'google', name: 'Google', href: googleLoginUrl, lastUsed: true },
  { id: 'github', name: 'GitHub' },
  { id: 'apple', name: 'Apple' },
]

/** "Continue with …" buttons. Only providers with an OAuth URL are enabled. */
export default function SocialProviders() {
  return (
    <>
      {PROVIDERS.map(({ id, name, href, lastUsed }) => {
        const content = (
          <>
            <BrandLogo brand={id} size={18} className="kau-provider-logo" />
            <span>Continue with {name}</span>
            {!href && <span className="kau-tag">Soon</span>}
            {href && lastUsed && <span className="kau-tag kau-tag--last neu-well">Last used</span>}
          </>
        )
        return href ? (
          <NeuButton key={id} block href={href()} onClick={clearLoggedOutMarker}>{content}</NeuButton>
        ) : (
          <NeuButton key={id} block disabled title="Coming soon">{content}</NeuButton>
        )
      })}
    </>
  )
}

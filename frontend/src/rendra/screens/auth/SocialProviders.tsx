import type { ComponentType } from 'react'
import { clearLoggedOutMarker, googleLoginUrl } from '../../../auth/api'
import { NeuButton } from '../../../components/neu'
import { AppleLogo, GitHubLogo, GoogleLogo } from './ProviderLogos'

type Provider = {
  id: 'google' | 'github' | 'apple'
  name: string
  Logo: ComponentType<{ className?: string }>
  /** OAuth start URL; providers without one are shown as "Soon". */
  href?: () => string
  lastUsed?: boolean
}

const PROVIDERS: readonly Provider[] = [
  { id: 'google', name: 'Google', Logo: GoogleLogo, href: googleLoginUrl, lastUsed: true },
  { id: 'github', name: 'GitHub', Logo: GitHubLogo },
  { id: 'apple', name: 'Apple', Logo: AppleLogo },
]

/** "Continue with …" buttons. Only providers with an OAuth URL are enabled. */
export default function SocialProviders() {
  return (
    <>
      {PROVIDERS.map(({ id, name, Logo, href, lastUsed }) => {
        const content = (
          <>
            <Logo className="kau-provider-logo" />
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

import { siAirtable, siApple, siGithub, siGooglesheets, siHubspot, siNotion, siResend, siStripe, type SimpleIcon } from 'simple-icons'

/** An official brand mark: one or more filled paths in a viewBox. `currentColor` follows the text colour. */
export type BrandMark = { title: string; viewBox: string; paths: readonly { d: string; fill: string }[] }

/** Black marks (GitHub, Notion…) follow the text colour so they stay visible in dark mode. */
function nearBlack(hex: string): boolean {
  const n = parseInt(hex, 16)
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255]
  return 0.2126 * r + 0.7152 * g + 0.0722 * b < 40
}

/** A single-colour mark from Simple Icons (CC0), in its brand colour. */
function simple(icon: SimpleIcon): BrandMark {
  return { title: icon.title, viewBox: '0 0 24 24', paths: [{ d: icon.path, fill: nearBlack(icon.hex) ? 'currentColor' : `#${icon.hex}` }] }
}

/** Multi-colour official marks that Simple Icons does not carry (paths from gilbarbara/logos, CC0, and Google's G). */
const GOOGLE: BrandMark = {
  title: 'Google',
  viewBox: '0 0 24 24',
  paths: [
    { fill: '#4285F4', d: 'M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z' },
    { fill: '#34A853', d: 'M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z' },
    { fill: '#FBBC05', d: 'M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z' },
    { fill: '#EA4335', d: 'M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z' },
  ],
}

const SLACK: BrandMark = {
  title: 'Slack',
  viewBox: '0 0 256 256',
  paths: [
    { fill: '#E01E5A', d: 'M53.841 161.32c0 14.832-11.987 26.82-26.819 26.82S.203 176.152.203 161.32c0-14.831 11.987-26.818 26.82-26.818H53.84zm13.41 0c0-14.831 11.987-26.818 26.819-26.818s26.819 11.987 26.819 26.819v67.047c0 14.832-11.987 26.82-26.82 26.82c-14.83 0-26.818-11.988-26.818-26.82z' },
    { fill: '#36C5F0', d: 'M94.07 53.638c-14.832 0-26.82-11.987-26.82-26.819S79.239 0 94.07 0s26.819 11.987 26.819 26.819v26.82zm0 13.613c14.832 0 26.819 11.987 26.819 26.819s-11.987 26.819-26.82 26.819H26.82C11.987 120.889 0 108.902 0 94.069c0-14.83 11.987-26.818 26.819-26.818z' },
    { fill: '#2EB67D', d: 'M201.55 94.07c0-14.832 11.987-26.82 26.818-26.82s26.82 11.988 26.82 26.82s-11.988 26.819-26.82 26.819H201.55zm-13.41 0c0 14.832-11.988 26.819-26.82 26.819c-14.831 0-26.818-11.987-26.818-26.82V26.82C134.502 11.987 146.489 0 161.32 0s26.819 11.987 26.819 26.819z' },
    { fill: '#ECB22E', d: 'M161.32 201.55c14.832 0 26.82 11.987 26.82 26.818s-11.988 26.82-26.82 26.82c-14.831 0-26.818-11.988-26.818-26.82V201.55zm0-13.41c-14.831 0-26.818-11.988-26.818-26.82c0-14.831 11.987-26.818 26.819-26.818h67.25c14.832 0 26.82 11.987 26.82 26.819s-11.988 26.819-26.82 26.819z' },
  ],
}

const TWILIO: BrandMark = {
  title: 'Twilio',
  viewBox: '0 0 256 256',
  paths: [
    { fill: '#F12E45', d: 'M128 0c70.656 0 128 57.344 128 128s-57.344 128-128 128S0 198.656 0 128S57.344 0 128 0m0 33.792c-52.224 0-94.208 41.984-94.208 94.208S75.776 222.208 128 222.208s94.208-41.984 94.208-94.208S180.224 33.792 128 33.792m31.744 99.328c14.704 0 26.624 11.92 26.624 26.624s-11.92 26.624-26.624 26.624s-26.624-11.92-26.624-26.624s11.92-26.624 26.624-26.624m-63.488 0c14.704 0 26.624 11.92 26.624 26.624s-11.92 26.624-26.624 26.624s-26.624-11.92-26.624-26.624s11.92-26.624 26.624-26.624m63.488-63.488c14.704 0 26.624 11.92 26.624 26.624s-11.92 26.624-26.624 26.624s-26.624-11.92-26.624-26.624s11.92-26.624 26.624-26.624m-63.488 0c14.704 0 26.624 11.92 26.624 26.624s-11.92 26.624-26.624 26.624s-26.624-11.92-26.624-26.624s11.92-26.624 26.624-26.624' },
  ],
}

/** Every third-party brand the app shows. Add new ones here; never use letter placeholders. */
export const BRANDS = {
  google: GOOGLE,
  github: simple(siGithub),
  apple: simple(siApple),
  slack: SLACK,
  stripe: simple(siStripe),
  googleSheets: simple(siGooglesheets),
  notion: simple(siNotion),
  resend: simple(siResend),
  twilio: TWILIO,
  hubspot: simple(siHubspot),
  airtable: simple(siAirtable),
} as const satisfies Record<string, BrandMark>

export type BrandId = keyof typeof BRANDS

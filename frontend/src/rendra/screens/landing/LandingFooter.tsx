const LINKS = [
  { label: 'Terms', href: '#terms' },
  { label: 'Privacy', href: '#privacy' },
  { label: 'Security', href: '#security' },
  { label: 'Status', href: '#status' },
] as const

export default function LandingFooter() {
  return (
    <footer className="lp-footer">
      <span>© {new Date().getFullYear()} Rendra</span>
      <nav aria-label="Legal">{LINKS.map((l) => <a key={l.label} href={l.href}>{l.label}</a>)}</nav>
    </footer>
  )
}

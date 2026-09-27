import { useState } from 'react'
import AppShell from '../AppShell'

/**
 * Connectors — Figma "13 · Connectors". A catalogue of integrations grouped into Connected / Popular.
 *
 * Honest scope: there is no connectors backend yet (real OAuth to GitHub/Slack/etc. is a large future
 * effort). The Connect/Disconnect toggle is persisted client-side (localStorage) so the UI is fully
 * interactive and the seam is ready; wiring each provider's OAuth is deferred to a dedicated point.
 */

type Conn = { id: string; name: string; cat: string; mono: string; bg: string; fg: string; desc: string }

const CONNECTORS: Conn[] = [
  { id: 'gh', name: 'GitHub', cat: 'Code', mono: 'GH', bg: '#16151A', fg: '#FFFFFF', desc: 'Sync every project to a repository and open pull requests.' },
  { id: 'sl', name: 'Slack', cat: 'Messaging', mono: 'S', bg: '#F1EEFF', fg: '#3D30C9', desc: 'Send alerts and let workflows post to channels.' },
  { id: 'st', name: 'Stripe', cat: 'Payments', mono: 'St', bg: '#E4F1F7', fg: '#135E84', desc: 'Checkout, subscriptions and invoices.' },
  { id: 'gs', name: 'Google Sheets', cat: 'Data', mono: 'GS', bg: '#E7F8EF', fg: '#0B6B45', desc: 'Read and write rows from any sheet.' },
  { id: 'no', name: 'Notion', cat: 'Docs', mono: 'N', bg: '#F1EEE8', fg: '#16151A', desc: 'Use pages and databases as content.' },
  { id: 'rs', name: 'Resend', cat: 'Email', mono: 'R', bg: '#FFEDE6', fg: '#A33511', desc: 'Transactional email from your app.' },
  { id: 'tw', name: 'Twilio', cat: 'SMS & voice', mono: 'Tw', bg: '#FDE7EF', fg: '#9E1F50', desc: 'Text message reminders and OTP login.' },
  { id: 'hs', name: 'HubSpot', cat: 'CRM', mono: 'H', bg: '#FFF5D6', fg: '#7A4F00', desc: 'Sync contacts, deals and forms.' },
  { id: 'at', name: 'Airtable', cat: 'Data', mono: 'At', bg: '#E4F1F7', fg: '#135E84', desc: 'Bases as a quick backend.' },
]

const KEY = 'kiln_connectors'
function loadConnected(): Set<string> {
  try { return new Set(JSON.parse(localStorage.getItem(KEY) || '["gh","sl"]') as string[]) } catch { return new Set(['gh', 'sl']) }
}

export default function Connectors() {
  const [connected, setConnected] = useState<Set<string>>(loadConnected)

  const toggle = (id: string) => {
    setConnected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      localStorage.setItem(KEY, JSON.stringify([...next]))
      return next
    })
  }

  const groups = [
    { title: 'Connected', items: CONNECTORS.filter((c) => connected.has(c.id)) },
    { title: 'Popular', items: CONNECTORS.filter((c) => !connected.has(c.id)) },
  ].filter((g) => g.items.length)

  return (
    <AppShell active="connectors">
      <div className="kap-page">
        <div className="kap-ph">
          <div>
            <h1>Connectors</h1>
            <p>Connect a service once — every project in this workspace can use it.</p>
          </div>
        </div>

        {groups.map((g) => (
          <div className="kap-conn-group" key={g.title}>
            <span>{g.title}</span>
            <div className="kap-conn-grid">
              {g.items.map((c) => {
                const on = connected.has(c.id)
                return (
                  <div className="kap-conn" key={c.id}>
                    <div className="kap-conn-top">
                      <span className="kap-conn-mono" style={{ background: c.bg, color: c.fg }}>{c.mono}</span>
                      <span style={{ flexGrow: 1, minWidth: 0 }}><b>{c.name}</b><small>{c.cat}</small></span>
                      <button type="button" className={`kap-conn-btn ${on ? 'on' : ''}`} onClick={() => toggle(c.id)}>
                        {on ? 'Connected' : 'Connect'}
                      </button>
                    </div>
                    <p>{c.desc}</p>
                  </div>
                )
              })}
            </div>
          </div>
        ))}
      </div>
    </AppShell>
  )
}

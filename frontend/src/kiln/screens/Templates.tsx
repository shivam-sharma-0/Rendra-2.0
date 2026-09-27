import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { useStartProject } from '../../api/queries'
import AppShell from '../AppShell'
import { Spinner } from '../../components/ui/States'

/**
 * Templates — Figma "12 · Templates" (rendered inside the shell). A curated static gallery; picking
 * one starts a real project from that template's prompt (via useStartProject) and opens it. The
 * catalogue is client-side content (no templates backend needed); only project creation hits the API.
 */

type Tpl = { name: string; kind: string; cat: string; prompt: string; bg: string; fg: string; ac: string; card: string }

const CATS: { id: string; label: string }[] = [
  { id: 'all', label: 'All' }, { id: 'web', label: 'Websites' }, { id: 'dash', label: 'Dashboards' },
  { id: 'ai', label: 'AI agents' }, { id: 'shop', label: 'E‑commerce' }, { id: 'tool', label: 'Internal tools' },
]

const TEMPLATES: Tpl[] = [
  { name: 'Habit tracker', kind: 'Productivity', cat: 'tool', bg: '#FFEDE6', fg: '#16151A', ac: '#FF5B2E', card: '#FFFFFF', prompt: 'Build a habit tracker app where I can add daily habits, mark them complete, and see streaks and a weekly progress chart.' },
  { name: 'Restaurant booking', kind: 'Hospitality', cat: 'web', bg: '#1F2A24', fg: '#EAF4EE', ac: '#7BE0A6', card: '#2C3A32', prompt: 'Build a restaurant website with a menu, photo gallery, and an online table booking form that collects date, time and party size.' },
  { name: 'Creator storefront', kind: 'E‑commerce', cat: 'shop', bg: '#F1EEFF', fg: '#221A66', ac: '#5B4CF5', card: '#FFFFFF', prompt: 'Build an online storefront for a creator to sell digital products, with a product grid, product pages and a simple cart.' },
  { name: 'CRM lite', kind: 'Sales dashboard', cat: 'dash', bg: '#16151A', fg: '#F6F4EF', ac: '#FFB23E', card: '#2A2930', prompt: 'Build a lightweight CRM dashboard with a contacts list, deal pipeline stages, and charts summarising deals by stage.' },
  { name: 'Support triage agent', kind: 'AI agent', cat: 'ai', bg: '#FFF5D6', fg: '#3B2A00', ac: '#E23E7B', card: '#FFFFFF', prompt: 'Build an AI support triage agent that reads incoming messages, categorises them by topic and urgency, and suggests a reply.' },
  { name: 'Invoice studio', kind: 'Finance tool', cat: 'tool', bg: '#E4F1F7', fg: '#0D2A38', ac: '#1B7FB0', card: '#FFFFFF', prompt: 'Build an invoicing tool to create and manage invoices, add line items, track paid/unpaid status and export as PDF.' },
]

export default function Templates() {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const start = useStartProject()
  const [cat, setCat] = useState('all')
  const [error, setError] = useState('')

  const shown = cat === 'all' ? TEMPLATES : TEMPLATES.filter((t) => t.cat === cat)

  const use = async (t: Tpl) => {
    if (start.isPending) return
    setError('')
    try {
      const project = await start.mutateAsync({ prompt: t.prompt, mode: 'BUILD', workspaceId: selectedId ?? undefined })
      nav(`/project/${project.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start from this template.')
    }
  }

  return (
    <AppShell active="templates">
      <div className="kap-page">
        <div className="kap-ph">
          <div>
            <h1>Start from something <em>beautiful</em></h1>
            <p>Pick a template, then ask Kiln to make it yours.</p>
          </div>
          {start.isPending && <Spinner />}
        </div>

        <div className="kap-cats">
          {CATS.map((c) => (
            <button key={c.id} type="button" className={`kap-cat ${cat === c.id ? 'on' : ''}`} onClick={() => setCat(c.id)}>{c.label}</button>
          ))}
        </div>

        {error && <p style={{ margin: 0, color: '#C63B2B', fontSize: 13 }} role="alert">{error}</p>}

        <div className="kap-tpl-grid">
          {shown.map((t) => (
            <div className="kap-tpl" key={t.name}>
              <div className="kap-tpl-thumb" style={{ background: t.bg }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ height: 8, width: 46, borderRadius: 4, background: t.fg, opacity: 0.8 }} />
                  <span style={{ height: 18, width: 50, borderRadius: 9, background: t.ac }} />
                </div>
                <span className="t1" style={{ background: t.fg }} />
                <span className="t2" style={{ background: t.fg }} />
                <div className="row"><span style={{ background: t.card }} /><span style={{ background: t.card }} /><span style={{ background: t.card }} /></div>
              </div>
              <div className="kap-tpl-meta">
                <span style={{ flexGrow: 1 }}><b>{t.name}</b><small>{t.kind}</small></span>
                <button type="button" className="kap-tpl-use" onClick={() => void use(t)} disabled={start.isPending}>Use template</button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </AppShell>
  )
}

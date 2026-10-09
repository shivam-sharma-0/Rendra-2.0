import type { PreviewColours } from '../../ui/MiniPreview'

export type TemplateCategory = 'all' | 'web' | 'dash' | 'ai' | 'shop' | 'tool'

export const CATEGORIES: readonly { id: TemplateCategory; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'web', label: 'Websites' },
  { id: 'dash', label: 'Dashboards' },
  { id: 'ai', label: 'AI agents' },
  { id: 'shop', label: 'E‑commerce' },
  { id: 'tool', label: 'Internal tools' },
]

export type Template = { name: string; kind: string; category: Exclude<TemplateCategory, 'all'>; prompt: string; colours: PreviewColours }

/** A curated, client-side catalogue: picking one starts a project from its prompt. */
export const TEMPLATES: readonly Template[] = [
  { name: 'Habit tracker', kind: 'Productivity', category: 'tool', colours: { bg: '#FFEDE6', fg: '#16151A', accent: '#FF5B2E', card: '#FFFFFF' },
    prompt: 'Build a habit tracker app where I can add daily habits, mark them complete, and see streaks and a weekly progress chart.' },
  { name: 'Restaurant booking', kind: 'Hospitality', category: 'web', colours: { bg: '#1F2A24', fg: '#EAF4EE', accent: '#7BE0A6', card: '#2C3A32' },
    prompt: 'Build a restaurant website with a menu, photo gallery, and an online table booking form that collects date, time and party size.' },
  { name: 'Creator storefront', kind: 'E‑commerce', category: 'shop', colours: { bg: '#F1EEFF', fg: '#221A66', accent: '#5B4CF5', card: '#FFFFFF' },
    prompt: 'Build an online storefront for a creator to sell digital products, with a product grid, product pages and a simple cart.' },
  { name: 'CRM lite', kind: 'Sales dashboard', category: 'dash', colours: { bg: '#16151A', fg: '#F6F4EF', accent: '#FFB23E', card: '#2A2930' },
    prompt: 'Build a lightweight CRM dashboard with a contacts list, deal pipeline stages, and charts summarising deals by stage.' },
  { name: 'Support triage agent', kind: 'AI agent', category: 'ai', colours: { bg: '#FFF5D6', fg: '#3B2A00', accent: '#E23E7B', card: '#FFFFFF' },
    prompt: 'Build an AI support triage agent that reads incoming messages, categorises them by topic and urgency, and suggests a reply.' },
  { name: 'Invoice studio', kind: 'Finance tool', category: 'tool', colours: { bg: '#E4F1F7', fg: '#0D2A38', accent: '#1B7FB0', card: '#FFFFFF' },
    prompt: 'Build an invoicing tool to create and manage invoices, add line items, track paid/unpaid status and export as PDF.' },
]

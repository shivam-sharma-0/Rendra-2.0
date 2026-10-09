import type { ProjectType } from '../../../api/projects'
import { ICON } from '../../icons'

/** Example ideas the prompt box types out as a rotating placeholder. */
export const PROMPT_IDEAS = [
  'a booking app for my yoga studio',
  'a CRM that my sales team will love',
  'a portfolio with a dark, editorial feel',
  'an inventory tracker with barcode scan',
] as const

export const PROJECT_MODES: readonly { value: ProjectType; label: string }[] = [
  { value: 'BUILD', label: 'Build' },
  { value: 'PLAN', label: 'Plan' },
  { value: 'CHAT', label: 'Chat' },
]

/** One-click starters under the prompt box. */
export const PROMPT_CHIPS: readonly { label: string; icon: string; prompt: string }[] = [
  { label: 'SaaS dashboard', icon: ICON.chart, prompt: 'a SaaS analytics dashboard with charts and team roles' },
  { label: 'Online store', icon: ICON.cart, prompt: 'an online store for handmade candles with cart and checkout' },
  { label: 'Portfolio', icon: ICON.window, prompt: 'a portfolio site for a product designer' },
  { label: 'Booking app', icon: ICON.calendar, prompt: 'a booking app for a hair salon with reminders' },
  { label: 'Internal tool', icon: ICON.wrench, prompt: 'an internal tool to approve expense requests' },
]

/** Template previews for the marquee; colours paint a miniature of each app. */
export type TemplatePreview = { name: string; kind: string; bg: string; fg: string; accent: string; card: string }

export const TEMPLATE_PREVIEWS: readonly TemplatePreview[] = [
  { name: 'Habit tracker', kind: 'Productivity', bg: '#FFEDE6', fg: '#16151A', accent: '#FF5B2E', card: '#FFFFFF' },
  { name: 'Restaurant booking', kind: 'Hospitality', bg: '#1F2A24', fg: '#EAF4EE', accent: '#7BE0A6', card: '#2C3A32' },
  { name: 'Creator storefront', kind: 'E-commerce', bg: '#F1EEFF', fg: '#221A66', accent: '#5B4CF5', card: '#FFFFFF' },
  { name: 'CRM lite', kind: 'Sales', bg: '#16151A', fg: '#F6F4EF', accent: '#FFB23E', card: '#2A2930' },
  { name: 'Event landing', kind: 'Marketing', bg: '#FFF5D6', fg: '#3B2A00', accent: '#E23E7B', card: '#FFFFFF' },
  { name: 'Invoice studio', kind: 'Finance', bg: '#E4F1F7', fg: '#0D2A38', accent: '#1B7FB0', card: '#FFFFFF' },
]

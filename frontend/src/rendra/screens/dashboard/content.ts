import type { ProjectType } from '../../../api/projects'

export type ModeOption = { id: ProjectType; label: string; desc: string; icon: string }

export const MODES: readonly ModeOption[] = [
  { id: 'BUILD', label: 'Build', desc: 'Writes and runs code for your app', icon: 'M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z' },
  { id: 'PLAN', label: 'Plan', desc: 'Maps pages, data and steps before building', icon: 'M9 5h11M9 12h11M9 19h11M4 5h.01M4 12h.01M4 19h.01' },
  { id: 'CHAT', label: 'Chat', desc: 'Ask questions without changing code', icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z' },
]

/** One-click starters under the composer; clicking one fills the prompt. */
export const IDEAS: readonly { label: string; icon: string; text: string }[] = [
  { label: 'Client portal', icon: 'M16 21v-2a4 4 0 0 0-8 0v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', text: 'Build a client portal with invoices and file sharing' },
  { label: 'AI chatbot', icon: 'M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6', text: 'Build an AI support chatbot trained on my FAQ' },
  { label: 'Landing page', icon: 'M4 5h16v14H4zM4 9h16', text: 'Build a landing page for my mobile app launch' },
  { label: 'Dashboard', icon: 'M4 20V10M10 20V4M16 20v-7M22 20H2', text: 'Build a sales dashboard with charts and filters' },
]

/** Rotating placeholder examples, per mode. */
export const PLACEHOLDERS: Record<ProjectType, readonly string[]> = {
  BUILD: [
    'Build a landing page for my mobile app...',
    'Build a real-time chat application...',
    'Build a dashboard with analytics charts...',
    'Build a to-do list with drag and drop...',
  ],
  PLAN: [
    'Plan the architecture for a SaaS platform...',
    'Draft a data model for an e-commerce store...',
    'Map out the user journey for a booking app...',
  ],
  CHAT: [
    'Ask Rendra anything about your projects...',
    'How do I deploy this application?',
    'What is the best way to handle authentication?',
  ],
}

export function greetingFor(date: Date): string {
  const h = date.getHours()
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

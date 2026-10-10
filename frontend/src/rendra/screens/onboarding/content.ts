import type { AppIconName } from '../../../components/icons'

export type InviteRole = 'Editor' | 'Viewer' | 'Admin'
export const INVITE_ROLES: readonly InviteRole[] = ['Editor', 'Viewer', 'Admin']

export const ROLES: readonly { id: string; label: string; icon: AppIconName }[] = [
  { id: 'founder', label: 'Founder', icon: 'lightning' },
  { id: 'dev', label: 'Developer', icon: 'code' },
  { id: 'design', label: 'Designer', icon: 'palette' },
  { id: 'pm', label: 'Product manager', icon: 'details' },
  { id: 'mkt', label: 'Marketer', icon: 'megaphone' },
  { id: 'agency', label: 'Agency', icon: 'buildings' },
  { id: 'student', label: 'Student', icon: 'graduationCap' },
  { id: 'other', label: 'Something else', icon: 'plus' },
]

export const GOALS: readonly { id: string; label: string }[] = [
  { id: 'web', label: 'Website' }, { id: 'tool', label: 'Internal tool' }, { id: 'saas', label: 'SaaS product' },
  { id: 'ai', label: 'AI agent or chatbot' }, { id: 'shop', label: 'Online store' }, { id: 'mobile', label: 'Mobile-friendly app' },
]

export const KINDS: readonly { id: string; label: string; desc: string; icon: AppIconName }[] = [
  { id: 'solo', label: 'Just me', desc: 'Personal projects and experiments', icon: 'user' },
  { id: 'team', label: 'My team', desc: 'Build together, share credits and connectors', icon: 'users' },
  { id: 'clients', label: 'My clients', desc: 'Hand projects over and invite clients as viewers', icon: 'briefcase' },
]

/** Workspace badge colours. */
export const COLORS: readonly { name: string; value: string }[] = [
  { name: 'Ink', value: '#16151A' }, { name: 'Ember', value: '#C2410C' }, { name: 'Iris', value: '#5B4CF5' },
  { name: 'Pine', value: '#0B7F58' }, { name: 'Rose', value: '#C92A66' },
]

export const STEPS: readonly { title: string; sub: string }[] = [
  { title: 'About you', sub: 'Your role and what you want to build' },
  { title: 'Your workspace', sub: "Name it and choose who it's for" },
  { title: 'Invite your team', sub: 'Build in the same project, live' },
]

/** Avatar colours for invited teammates in the preview. */
export const FACE_COLORS = ['#C92A66', '#1B7FB0', '#0B7F58', '#9A6000', '#5B4CF5']

export const slugify = (value: string) =>
  value.toLowerCase().replace(/['’]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')

import type { Project } from '../api/projects'

/** Shared presentational helpers for the app-shell screens (kept in one place — DRY). */

export function Ico({ d, size = 18, sw = 1.9 }: { d: string; size?: number; sw?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
  )
}

export function initials(text: string): string {
  return text.split(/\s|@/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || 'K'
}

/** Human "x min/h/days ago" for a UTC ISO timestamp. */
export function relTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const m = Math.round(diff / 60000)
  if (m < 1) return 'just now'
  if (m < 60) return `${m} min ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} h ago`
  const d = Math.round(h / 24)
  return d === 1 ? 'yesterday' : `${d} days ago`
}

export function statusOf(p: Project): { label: string; dot: string; bg: string; ink: string } {
  switch (p.deploymentStatus) {
    case 'LIVE': return { label: 'Live', dot: '#16A36A', bg: '#E7F8EF', ink: '#0B6B45' }
    case 'PENDING': return { label: 'Building', dot: '#5B4CF5', bg: '#F1EEFF', ink: '#3D30C9' }
    case 'FAILED': return { label: 'Failed', dot: '#C63B2B', bg: '#FDECEA', ink: '#B42318' }
    default: return { label: 'Draft', dot: '#8A8791', bg: '#F1EEE8', ink: '#4A4852' }
  }
}

/** Deterministic thumbnail palette so a project keeps the same colours across views. */
export const CARD_PALETTE = [
  { bg: '#F1EEFF', fg: '#221A66', ac: '#5B4CF5', card: '#FFFFFF' },
  { bg: '#FFEDE6', fg: '#16151A', ac: '#FF5B2E', card: '#FFFFFF' },
  { bg: '#E4F1F7', fg: '#0D2A38', ac: '#1B7FB0', card: '#FFFFFF' },
  { bg: '#1F2A24', fg: '#EAF4EE', ac: '#0E9F6E', card: '#2C3A32' },
  { bg: '#16151A', fg: '#F6F4EF', ac: '#E23E7B', card: '#2A2930' },
  { bg: '#FFF5D6', fg: '#3B2A00', ac: '#C27A00', card: '#FFFFFF' },
]

/** Stable palette per project id, so colours don't shuffle when the list is filtered/sorted. */
export function paletteFor(id: string) {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return CARD_PALETTE[h % CARD_PALETTE.length]
}

/** Compact "x ago" relative time, e.g. "4 hours ago", "2 days ago". */
export function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const m = Math.floor(diff / 60_000)
  if (m < 1) return 'just now'
  if (m < 60) return `${m} minute${m > 1 ? 's' : ''} ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} hour${h > 1 ? 's' : ''} ago`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} day${d > 1 ? 's' : ''} ago`
  const mo = Math.floor(d / 30)
  if (mo < 12) return `${mo} month${mo > 1 ? 's' : ''} ago`
  const y = Math.floor(mo / 12)
  return `${y} year${y > 1 ? 's' : ''} ago`
}

import { useStoredState } from '../../../lib/useStoredState'

/** Project ids starred in this browser (the backend has no starred flag yet). */
export function useStarredProjects() {
  const [starred, setStarred] = useStoredState<ReadonlySet<string>>(
    'rendra_starred',
    (raw) => { try { return new Set(JSON.parse(raw || '[]') as string[]) } catch { return new Set() } },
    (set) => JSON.stringify([...set]),
  )
  const toggle = (id: string) => {
    const next = new Set(starred)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setStarred(next)
  }
  return { starred, toggle }
}

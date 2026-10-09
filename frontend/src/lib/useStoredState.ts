import { useState } from 'react'

/** State that is also remembered in localStorage (falls back to memory when storage is unavailable). */
export function useStoredState<T>(key: string, parse: (raw: string | null) => T, serialize: (value: T) => string) {
  const [value, setValue] = useState<T>(() => {
    try { return parse(localStorage.getItem(key)) } catch { return parse(null) }
  })
  const update = (next: T) => {
    setValue(next)
    try { localStorage.setItem(key, serialize(next)) } catch { /* remembered for this visit only */ }
  }
  return [value, update] as const
}

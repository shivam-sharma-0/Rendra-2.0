type Row = Record<string, unknown> & { id: string; createdAt: string; updatedAt: string }
const fixed = new Set(['id', 'createdAt', 'updatedAt'])

function newId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID() : `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

export function localStore<T extends { id: string }, I>(key: string, search: string[], filter?: string) {
  const read = (): Row[] => {
    try {
      const value: unknown = JSON.parse(window.localStorage.getItem(key) ?? '[]')
      return Array.isArray(value) ? value.filter((row): row is Row => !!row && typeof row === 'object' && typeof row.id === 'string') : []
    } catch {
      return []
    }
  }
  const write = (rows: Row[]) => window.localStorage.setItem(key, JSON.stringify(rows))
  const missing = () => new Error('Record not found (404)')
  const values = (input: unknown) => Object.entries(input && typeof input === 'object' ? input as Record<string, unknown> : {})
    .filter(([name]) => !fixed.has(name))
  return {
    async list(query: Record<string, string> = {}): Promise<T[]> {
      const text = (query.q ?? '').trim().toLowerCase()
      const chosen = filter ? query[filter] : undefined
      return read()
        .filter(row => !text || search.some(name => String(row[name] ?? '').toLowerCase().includes(text)))
        .filter(row => !filter || chosen === undefined || chosen === '' || String(row[filter]) === chosen)
        .sort((first, second) => String(second.createdAt ?? '').localeCompare(String(first.createdAt ?? ''))) as unknown as T[]
    },
    async get(id: string): Promise<T> {
      const row = read().find(candidate => candidate.id === id)
      if (!row) throw missing()
      return row as unknown as T
    },
    async create(input: I): Promise<T> {
      const now = new Date().toISOString()
      const row: Row = { ...Object.fromEntries(values(input).filter(([, value]) => value !== null && value !== undefined)), id: newId(), createdAt: now, updatedAt: now }
      write([row, ...read()])
      return row as unknown as T
    },
    async update(id: string, input: Partial<I>): Promise<T> {
      const rows = read()
      const index = rows.findIndex(candidate => candidate.id === id)
      if (index < 0) throw missing()
      const next: Row = { ...rows[index]!, updatedAt: new Date().toISOString() }
      for (const [name, value] of values(input)) {
        if (value === null) delete next[name]
        else if (value !== undefined) next[name] = value
      }
      rows[index] = next
      write(rows)
      return next as unknown as T
    },
    async remove(id: string): Promise<{ id: string }> {
      const rows = read()
      if (!rows.some(candidate => candidate.id === id)) throw missing()
      write(rows.filter(candidate => candidate.id !== id))
      return { id }
    },
  }
}

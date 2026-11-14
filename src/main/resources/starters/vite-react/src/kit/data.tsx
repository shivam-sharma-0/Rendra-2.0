import type { ReactNode } from 'react'

export type Column<T> = { key: string; header: ReactNode; render?: (row: T) => ReactNode; align?: 'left' | 'right' }

export function DataTable<T>({ columns, rows, rowKey, empty, onRowClick }: {
  columns: Column<T>[]; rows: T[]; rowKey: (row: T) => string; empty?: ReactNode; onRowClick?: (row: T) => void
}) {
  if (rows.length === 0 && empty) return <>{empty}</>
  return <div className="app-table-wrap">
    <table>
      <thead><tr>{columns.map(column => <th key={column.key} style={{ textAlign: column.align ?? 'left' }}>{column.header}</th>)}</tr></thead>
      <tbody>
        {rows.map(row => <tr key={rowKey(row)} className={onRowClick ? 'app-row--interactive' : undefined}
          onClick={onRowClick ? () => onRowClick(row) : undefined}>
          {columns.map(column => <td key={column.key} style={{ textAlign: column.align ?? 'left' }}>
            {column.render ? column.render(row) : String((row as Record<string, unknown>)[column.key] ?? '')}
          </td>)}
        </tr>)}
      </tbody>
    </table>
  </div>
}

export function Avatar({ name, src, size = 40 }: { name: string; src?: string; size?: number }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]?.toUpperCase()).join('') || '?'
  return src
    ? <img className="app-avatar" src={src} alt={name} width={size} height={size} />
    : <span className="app-avatar" style={{ width: size, height: size }} aria-hidden="true">{initials}</span>
}

export function RankList({ entries, empty }: {
  entries: { id: string; name: string; score: ReactNode; detail?: ReactNode; highlight?: boolean }[]; empty?: ReactNode
}) {
  if (entries.length === 0 && empty) return <>{empty}</>
  return <ol className="app-rank-list">
    {entries.map((entry, index) => <li key={entry.id} className={entry.highlight ? 'app-rank-list__item is-current' : 'app-rank-list__item'}>
      <span className={`app-rank-list__rank app-rank-list__rank--${index < 3 ? index + 1 : 'rest'}`}>{index + 1}</span>
      <Avatar name={entry.name} size={36} />
      <span className="app-rank-list__name">
        <strong>{entry.name}</strong>
        {entry.detail && <span className="app-muted">{entry.detail}</span>}
      </span>
      <span className="app-rank-list__score">{entry.score}</span>
    </li>)}
  </ol>
}

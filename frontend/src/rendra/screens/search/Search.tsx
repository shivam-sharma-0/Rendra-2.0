import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useProjects } from '../../../api/queries'
import { AppIcon, type AppIconName } from '../../../components/icons'
import { cx } from '../../../lib/cx'
import { timeAgo } from '../../../lib/time'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import { hasBackground } from './searchRoute'
import './search.css'

type Item = { id: string; label: string; hint?: string; icon: AppIconName; to: string }

const PAGES: Item[] = [
  { id: 'p-new', label: 'New project', icon: 'newProject', to: '/dashboard' },
  { id: 'p-projects', label: 'Projects', icon: 'projects', to: '/projects' },
  { id: 'p-chats', label: 'Chats', icon: 'chats', to: '/chats' },
  { id: 'p-templates', label: 'Templates', icon: 'templates', to: '/templates' },
  { id: 'p-connectors', label: 'Connectors', icon: 'connectors', to: '/connectors' },
  { id: 'p-members', label: 'Members', hint: 'Settings', icon: 'users', to: '/settings/members' },
  { id: 'p-appearance', label: 'Appearance', hint: 'Settings', icon: 'palette', to: '/settings/appearance' },
]

const includes = (text: string | null | undefined, q: string) => !!text && text.toLowerCase().includes(q)

/**
 * Command palette at /search (⌘K), shown over the page it was opened from (see `searchRoute`):
 * find a project or jump to a page. ↑/↓ to move, Enter to open, Esc or a click outside to close.
 */
export default function Search() {
  const nav = useNavigate()
  const location = useLocation()
  const { selectedId } = useWorkspace()
  const { data: page, isLoading } = useProjects(selectedId)
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  const query = q.trim().toLowerCase()
  const groups = useMemo(() => {
    const projects = (page?.content ?? [])
      .filter((p) => !query || includes(p.name, query) || includes(p.initialPrompt, query))
      .slice(0, query ? 8 : 4)
      .map<Item>((p) => ({ id: p.id, label: p.name, hint: timeAgo(p.lastActivityAt), icon: p.type === 'CHAT' ? 'chat' : 'projects', to: `/project/${p.id}` }))
    const pages = PAGES.filter((p) => !query || includes(p.label, query) || includes(p.hint, query))
    return [
      { title: query ? 'Projects' : 'Recent projects', items: projects },
      { title: 'Go to', items: pages },
    ].filter((g) => g.items.length)
  }, [page, query])
  const items = useMemo(() => groups.flatMap((g) => g.items), [groups])
  const current = Math.min(active, Math.max(0, items.length - 1))

  /** Back to the page underneath; when /search was opened directly that page is the dashboard. */
  const close = () => {
    if (hasBackground(location)) nav(-1)
    else nav('/dashboard', { replace: true })
  }
  // Replace the /search entry, so Back from the opened page skips the palette.
  const open = (item: Item | undefined) => { if (item) nav(item.to, { replace: true }) }

  useEffect(() => {
    listRef.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [current])

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (!items.length) return
      const step = e.key === 'ArrowDown' ? 1 : -1
      setActive((current + step + items.length) % items.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      open(items[current])
    } else if (e.key === 'Escape') {
      e.preventDefault()
      close()
    }
  }

  return (
    <div className="sr" onMouseDown={(e) => { if (e.target === e.currentTarget) close() }}>
      <div className="sr-palette neu-panel" role="dialog" aria-modal="true" aria-label="Search">
        <div className="sr-field">
          <AppIcon name="search" size={20} />
          <input
            autoFocus
            role="combobox"
            aria-expanded={items.length > 0}
            aria-controls="sr-list"
            aria-activedescendant={items[current] ? `sr-${items[current].id}` : undefined}
            aria-autocomplete="list"
            placeholder="Search projects and pages…"
            value={q}
            onChange={(e) => { setQ(e.target.value); setActive(0) }}
            onKeyDown={onKeyDown}
          />
          <button type="button" className="sr-esc" onClick={close}>Esc</button>
        </div>

        <div className="sr-list" id="sr-list" role="listbox" aria-label="Results" ref={listRef}>
          {groups.map((g) => (
            <div key={g.title} role="group" aria-labelledby={`sr-g-${g.title}`} className="sr-group">
              <small id={`sr-g-${g.title}`}>{g.title}</small>
              {g.items.map((item) => {
                const index = items.indexOf(item)
                return (
                  <div
                    key={item.id}
                    id={`sr-${item.id}`}
                    role="option"
                    aria-selected={index === current}
                    className={cx('sr-item', index === current && 'is-active')}
                    onMouseMove={() => { if (index !== current) setActive(index) }}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => open(item)}
                  >
                    <span className="sr-item__icon"><AppIcon name={item.icon} /></span>
                    <span className="sr-item__label">{item.label}</span>
                    {item.hint && <small>{item.hint}</small>}
                    <span className="sr-item__enter" aria-hidden>↵</span>
                  </div>
                )
              })}
            </div>
          ))}
          {!items.length && (
            <p className="sr-empty">{isLoading ? 'Loading projects…' : <>Nothing matches “{q.trim()}”.</>}</p>
          )}
        </div>

        <footer className="sr-foot" aria-hidden>
          <span><kbd>↑</kbd><kbd>↓</kbd>Move</span>
          <span><kbd>↵</kbd>Open</span>
          <span><kbd>Esc</kbd>Close</span>
        </footer>
      </div>
    </div>
  )
}

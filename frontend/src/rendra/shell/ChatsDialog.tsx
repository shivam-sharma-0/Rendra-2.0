import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useProjects, useStartProject } from '../../api/queries'
import { Spinner } from '../../components/ui/States'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { ICON } from '../icons'
import { relTime } from '../parts'
import ShellIcon from './ShellIcon'

const PAGE_SIZE = 8
const FILTERS = { all: 'All', recent: 'Recent', pinned: 'Pinned' } as const
type Filter = keyof typeof FILTERS

/** Searchable, paged list of the workspace's chats, with "New chat". Mounted only while open. */
export default function ChatsDialog({ onClose }: { onClose: () => void }) {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const { data, isLoading } = useProjects(selectedId, { types: ['CHAT'] })
  const startProject = useStartProject()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState<Filter>('all')
  const [filterOpen, setFilterOpen] = useState(false)

  const chats = useMemo(() => {
    const all = data?.content ?? []
    const q = search.trim().toLowerCase()
    if (!q) return all
    return all.filter((p) => p.name.toLowerCase().includes(q) || (p.initialPrompt ?? '').toLowerCase().includes(q))
  }, [data, search])
  const totalPages = Math.max(1, Math.ceil(chats.length / PAGE_SIZE))
  const safePage = Math.min(page, totalPages - 1)
  const pageItems = chats.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  const close = () => { setFilterOpen(false); onClose() }
  const open = (id: string) => { onClose(); nav(`/project/${id}`) }
  const newChat = async () => {
    const p = await startProject.mutateAsync({ prompt: null, mode: 'CHAT', workspaceId: selectedId ?? undefined })
    open(p.id)
  }
  const updateSearch = (value: string) => { setSearch(value); setPage(0) }

  return (
    <div className="kap-dialog-overlay" onClick={close}>
      <div className="kap-chats-modal" onClick={(e) => e.stopPropagation()}>
        <div className="kap-chats-modal-hd">
          <button type="button" className="kap-chats-modal-ico-btn" onClick={onClose} aria-label="Back"><ShellIcon d={ICON.chevronLeft} size={17} /></button>
          <span className="kap-chats-modal-title">Chats</span>
          <div style={{ flexGrow: 1 }} />
          <button type="button" className="kap-chats-newbtn" disabled={startProject.isPending} onClick={() => void newChat()}>
            {startProject.isPending ? <Spinner size={13} /> : null} New chat
          </button>
          <button type="button" className="kap-chats-modal-ico-btn" onClick={onClose} aria-label="Close"><ShellIcon d={ICON.close} size={16} /></button>
        </div>

        <div className="kap-chats-modal-bar">
          <div className="kap-chats-search">
            <ShellIcon d={ICON.searchAlt} size={14} />
            <input type="search" placeholder="Search chats..." value={search} onChange={(e) => updateSearch(e.target.value)} aria-label="Search chats" autoFocus />
            {search && (
              <button type="button" className="kap-chats-clear" aria-label="Clear" onClick={() => updateSearch('')}><ShellIcon d={ICON.close} size={11} /></button>
            )}
          </div>
          <div style={{ position: 'relative' }}>
            <button type="button" className="kap-chats-filter-btn" onClick={() => setFilterOpen((v) => !v)}>
              {FILTERS[filter]}<ShellIcon d={ICON.chevronDown} size={12} />
            </button>
            {filterOpen && (
              <>
                <div className="kap-scrim" style={{ zIndex: 210 }} onClick={() => setFilterOpen(false)} />
                <div className="kap-pop" style={{ right: 0, top: 38, width: 130, zIndex: 211 }}>
                  {(Object.keys(FILTERS) as Filter[]).map((f) => (
                    <button key={f} type="button" className="kap-pop-item" onClick={() => { setFilter(f); setFilterOpen(false); setPage(0) }}>{FILTERS[f]}</button>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>

        <div className="kap-chats-modal-body">
          {isLoading ? (
            <div className="kap-chats-empty"><Spinner size={14} /></div>
          ) : chats.length === 0 ? (
            <div className="kap-chats-empty">{search ? `No chats match "${search}"` : 'No chats yet — start a new one.'}</div>
          ) : (
            pageItems.map((p, i) => (
              <button key={p.id} type="button" className={`kap-cm-row ${i < pageItems.length - 1 ? 'sep' : ''}`} onClick={() => open(p.id)}>
                <span className="kap-cm-info">
                  <span className="kap-cm-name">{p.name}{p.deploymentStatus === 'LIVE' && <i className="kap-cm-dot" />}</span>
                  <span className="kap-cm-time">{relTime(p.lastActivityAt)}</span>
                </span>
              </button>
            ))
          )}
        </div>

        {totalPages > 1 && (
          <div className="kap-chats-pager">
            <button type="button" className="kap-pager-btn" disabled={safePage === 0} onClick={() => setPage(safePage - 1)} aria-label="Previous"><ShellIcon d={ICON.chevronLeft} size={15} /></button>
            <span className="kap-pager-info">{safePage + 1} / {totalPages}</span>
            <button type="button" className="kap-pager-btn" disabled={safePage >= totalPages - 1} onClick={() => setPage(safePage + 1)} aria-label="Next"><ShellIcon d={ICON.chevronRight} size={15} /></button>
          </div>
        )}
      </div>
    </div>
  )
}

import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useDeleteProject, useProjects } from '../../api/queries'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { ICON } from '../icons'
import DeleteProjectDialog from './DeleteProjectDialog'
import ShellIcon from './ShellIcon'

const MAX_RECENTS = 7

/** The workspace's most recent projects, each with an actions menu (delete). */
export default function RecentsList({ openProjectId }: { openProjectId: string | null }) {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  // Same query key as the dashboard grid, so both share one request.
  const { data: page } = useProjects(selectedId)
  const recents = (page?.content ?? []).slice(0, MAX_RECENTS)
  const deleteProject = useDeleteProject(selectedId)
  const [menuFor, setMenuFor] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)

  const onDelete = (id: string) => {
    if (id === openProjectId) setConfirmDelete(id)
    else deleteProject.mutate(id)
    setMenuFor(null)
  }

  return (
    <div className="kap-recents">
      {confirmDelete && (
        <DeleteProjectDialog
          onCancel={() => setConfirmDelete(null)}
          onConfirm={() => {
            deleteProject.mutate(confirmDelete, { onSuccess: () => nav('/dashboard') })
            setConfirmDelete(null)
          }}
        />
      )}

      <span className="kap-recents-h">Recents</span>
      {recents.map((p) => (
        <div key={p.id} className="kap-recent-item" onMouseLeave={() => setMenuFor(null)}>
          <Link to={`/project/${p.id}`} className={openProjectId === p.id ? 'on' : ''}>
            <ShellIcon d={p.type === 'CHAT' ? ICON.chats : ICON.projects} size={18} />
            <span>{p.name}</span>
          </Link>
          <button type="button" className={`kap-recent-dots ${menuFor === p.id ? 'active' : ''}`} aria-label="Project actions"
            onClick={(e) => { e.preventDefault(); setMenuFor(menuFor === p.id ? null : p.id) }}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.4" /><circle cx="12" cy="12" r="1.4" /><circle cx="19" cy="12" r="1.4" /></svg>
          </button>
          {menuFor === p.id && (
            <div className="kap-pop kap-recent-pop">
              <button type="button" style={{ color: '#C63B2B' }} onClick={(e) => { e.preventDefault(); onDelete(p.id) }}>
                <ShellIcon d={ICON.trash} size={15} /> Delete
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

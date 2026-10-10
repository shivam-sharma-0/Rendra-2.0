import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useDeleteProject, useProjects } from '../../api/queries'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { AppIcon } from '../../components/icons'
import DeleteProjectDialog from './DeleteProjectDialog'

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
  const [confirmDelete, setConfirmDelete] = useState<{ id: string; name: string } | null>(null)
  const [deleteError, setDeleteError] = useState('')
  const deletingOpen = confirmDelete?.id === openProjectId
  const askDelete = (target: { id: string; name: string } | null) => { setDeleteError(''); setConfirmDelete(target) }

  return (
    <div className="kap-recents">
      {confirmDelete && (
        <DeleteProjectDialog
          projectName={confirmDelete.name}
          isOpen={deletingOpen}
          busy={deleteProject.isPending}
          error={deleteError}
          onCancel={() => askDelete(null)}
          onConfirm={() => {
            setDeleteError('')
            deleteProject.mutate(confirmDelete.id, {
              onSuccess: () => { setConfirmDelete(null); if (deletingOpen) nav('/dashboard') },
              onError: (err) => setDeleteError(err instanceof Error ? err.message : 'Could not delete the project.'),
            })
          }}
        />
      )}

      <span className="kap-recents-h">Recents</span>
      {recents.map((p) => (
        <div key={p.id} className="kap-recent-item" onMouseLeave={() => setMenuFor(null)}>
          <Link to={`/project/${p.id}`} className={openProjectId === p.id ? 'on' : ''}>
            <AppIcon name={p.type === 'CHAT' ? 'chat' : 'projects'} size={16} />
            <span>{p.name}</span>
          </Link>
          <button type="button" className={`kap-recent-dots ${menuFor === p.id ? 'active' : ''}`} aria-label="Project actions"
            onClick={(e) => { e.preventDefault(); setMenuFor(menuFor === p.id ? null : p.id) }}>
            <AppIcon name="more" size={16} />
          </button>
          {menuFor === p.id && (
            <div className="kap-pop kap-recent-pop">
              <button type="button" style={{ color: 'var(--neu-danger)' }} onClick={(e) => { e.preventDefault(); setMenuFor(null); askDelete({ id: p.id, name: p.name }) }}>
                <AppIcon name="trash" size={16} /> Delete
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

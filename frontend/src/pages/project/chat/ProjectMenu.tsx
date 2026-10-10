import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { type Project } from '../../../api/projects'
import { useDeleteProject, useRenameProject } from '../../../api/queries'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton, NeuDialog, NeuMenu, NeuMenuItem, NeuTextField } from '../../../components/neu'

const RENAME_ICON = 'M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4'
const PROJECTS_ICON = 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z'
const TRASH_ICON = 'M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14'

/**
 * The project's name in the chat header, opening a menu: rename it, go to all projects, or delete
 * it (after a confirmation).
 */
export function ProjectMenu({ project, onRenamed }: { project: Project | null; onRenamed: (project: Project) => void }) {
  const nav = useNavigate()
  const [open, setOpen] = useState(false)
  const [dialog, setDialog] = useState<'rename' | 'delete' | null>(null)
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const rename = useRenameProject(project?.workspaceId)
  const remove = useDeleteProject(project?.workspaceId)

  if (!project) return <span className="ws-project ws-project--loading">Loading…</span>

  const openDialog = (which: 'rename' | 'delete') => {
    setOpen(false)
    setError('')
    setName(project.name)
    setDialog(which)
  }
  const submitRename = async (e: FormEvent) => {
    e.preventDefault()
    const next = name.trim()
    if (!next || next === project.name) { setDialog(null); return }
    try {
      onRenamed(await rename.mutateAsync({ projectId: project.id, name: next }))
      setDialog(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not rename the project.')
    }
  }
  const confirmDelete = async () => {
    try {
      await remove.mutateAsync(project.id)
      nav('/dashboard', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete the project.')
    }
  }

  return (
    <div className="neu-menu-anchor ws-project-anchor">
      <button type="button" className="ws-project" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <span>{project.name}</span>
        <AppIcon name="chevronDown" />
      </button>
      <NeuMenu open={open} onClose={() => setOpen(false)} label="Project" className="ws-project-menu">
        <NeuMenuItem icon={RENAME_ICON} title="Rename" onSelect={() => openDialog('rename')} />
        <NeuMenuItem icon={PROJECTS_ICON} title="All projects" onSelect={() => { setOpen(false); nav('/projects') }} />
        <NeuMenuItem icon={TRASH_ICON} title="Delete project" hint="Removes its chats, files and builds" onSelect={() => openDialog('delete')} />
      </NeuMenu>

      {dialog === 'rename' && (
        <NeuDialog size="sm" title="Rename project" onClose={() => setDialog(null)}>
          <form className="ws-rename" onSubmit={(e) => void submitRename(e)}>
            <NeuTextField label="Name" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
            {error && <NeuAlert>{error}</NeuAlert>}
            <div className="ws-rename__actions">
              <NeuButton onClick={() => setDialog(null)}>Cancel</NeuButton>
              <NeuButton type="submit" variant="primary" disabled={!name.trim() || rename.isPending}>{rename.isPending ? 'Saving…' : 'Save'}</NeuButton>
            </div>
          </form>
        </NeuDialog>
      )}
      {dialog === 'delete' && (
        <NeuDialog
          size="sm"
          title={`Delete “${project.name}”?`}
          description="Its chats, files and builds are deleted for good. This cannot be undone."
          onClose={() => setDialog(null)}
          footer={(
            <>
              <NeuButton onClick={() => setDialog(null)}>Cancel</NeuButton>
              <NeuButton variant="danger" onClick={() => void confirmDelete()} disabled={remove.isPending}>{remove.isPending ? 'Deleting…' : 'Delete project'}</NeuButton>
            </>
          )}
        >
          {error && <NeuAlert>{error}</NeuAlert>}
        </NeuDialog>
      )}
    </div>
  )
}

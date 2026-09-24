import { useEffect, useState, type FormEvent } from 'react'
import { workspaceApi, type Workspace } from '../api/projects'
import { ApiClientError } from '../auth/api'

type Props = {
  open: boolean
  onClose: () => void
  onCreated: (workspace: Workspace) => void
}

/** Minimal dialog to create a new (team) workspace. */
export default function CreateWorkspaceModal({ open, onClose, onCreated }: Props) {
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy || !name.trim()) return
    setBusy(true)
    setError(null)
    try {
      const ws = await workspaceApi.create(name.trim())
      setName('')
      onCreated(ws)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not create workspace.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 grid place-items-center px-4" role="dialog" aria-modal="true" onMouseDown={onClose}>
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" />
      <div className="card animate-pop-in relative w-full max-w-sm rounded-3xl p-7" onMouseDown={(e) => e.stopPropagation()}>
        <h2 className="font-display text-lg font-bold text-text">New workspace</h2>
        <p className="mt-1 text-sm text-muted">Create a shared space to collaborate with your team.</p>
        <form onSubmit={submit} className="mt-5 space-y-3">
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Workspace name"
            maxLength={120}
            className="w-full rounded-[10px] border border-border bg-black/[0.03] px-3.5 py-2.5 text-sm text-text outline-none focus-visible:border-[color:var(--accent)] dark:bg-white/[0.04]"
          />
          {error && <p className="text-[13px] font-medium text-danger">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-[10px] px-3.5 py-2 text-[13px] font-semibold text-muted transition hover:text-text">
              Cancel
            </button>
            <button
              type="submit"
              disabled={busy || !name.trim()}
              className="rounded-[10px] bg-[color:var(--accent)] px-4 py-2 text-[13px] font-semibold text-[color:var(--accent-contrast)] transition hover:bg-[color:var(--accent-active)] disabled:opacity-50"
            >
              {busy ? 'Creating…' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

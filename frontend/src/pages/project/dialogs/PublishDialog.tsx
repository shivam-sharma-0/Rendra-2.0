import { useState } from 'react'
import { projectApi, type Project } from '../../../api/projects'

/* ── Publish (Figma 20) — real publish lifecycle → public URL ─────────────────────────────────── */
export function PublishDialog({ project, onClose, onChange }: { project: Project; onClose: () => void; onChange: (p: Project) => void }) {
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState('')
  const live = project.deploymentStatus === 'LIVE'
  const url = project.publishedUrl ?? ''

  const run = async (fn: () => Promise<Project>) => {
    setBusy(true); setError('')
    try { onChange(await fn()) } catch (err) { setError(err instanceof Error ? err.message : 'Something went wrong.') } finally { setBusy(false) }
  }

  return (
    <div className="pw-modal-scrim" onClick={onClose}>
      <div className="pw-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Publish this project">
        <header><b>{live ? 'Your project is live' : 'Publish your project'}</b><button className="pw-modal-x" onClick={onClose} aria-label="Close">✕</button></header>
        <p className="pw-modal-sub">{live ? 'Anyone with the link can view your published site.' : 'Make your project live at a public URL — you can take it down anytime.'}</p>
        {live && url && (
          <div className="pw-publish-url">
            <code>{url}</code>
            <button onClick={() => { void navigator.clipboard?.writeText(url).catch(() => {}); setCopied(true); setTimeout(() => setCopied(false), 1500) }}>{copied ? 'Copied' : 'Copy'}</button>
            <a href={url} target="_blank" rel="noreferrer">Open ↗</a>
          </div>
        )}
        {error && <p className="pw-error">{error}</p>}
        <div className="pw-publish-actions">
          {live ? (
            <>
              <button className="pw-hrestore" onClick={() => void run(() => projectApi.unpublish(project.id))} disabled={busy}>{busy ? 'Working…' : 'Unpublish'}</button>
              <button className="pw-share-send" onClick={() => void run(() => projectApi.publish(project.id))} disabled={busy}>{busy ? 'Working…' : 'Re-publish'}</button>
            </>
          ) : (
            <button className="pw-share-send" onClick={() => void run(() => projectApi.publish(project.id))} disabled={busy}>{busy ? 'Publishing…' : 'Publish now'}</button>
          )}
        </div>
        <p className="pw-publish-note">Static hosting — serves your project's built files. Server-rendered apps are coming soon.</p>
      </div>
    </div>
  )
}

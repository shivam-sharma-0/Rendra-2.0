import { useState } from 'react'
import { projectApi, type Project } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton, NeuDialog } from '../../../components/neu'
import './dialogs.css'

/** Publish the project at a public URL, re-publish it, or take it down. */
export function PublishDialog({ project, onClose, onChange }: { project: Project; onClose: () => void; onChange: (p: Project) => void }) {
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState('')
  const live = project.deploymentStatus === 'LIVE'
  const url = project.publishedUrl ?? ''

  const run = async (action: () => Promise<Project>) => {
    setBusy(true)
    setError('')
    try { onChange(await action()) } catch (err) { setError(err instanceof Error ? err.message : 'Something went wrong.') } finally { setBusy(false) }
  }
  const copy = () => {
    void navigator.clipboard?.writeText(url).catch(() => {})
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <NeuDialog
      title={live ? 'Your project is live' : 'Publish your project'}
      description={live ? 'Anyone with the link can view your published site.' : 'Make your project live at a public URL — you can take it down anytime.'}
      onClose={onClose}
      footer={live ? (
        <>
          <NeuButton onClick={() => void run(() => projectApi.unpublish(project.id))} disabled={busy}>{busy ? 'Working…' : 'Unpublish'}</NeuButton>
          <NeuButton variant="accent" onClick={() => void run(() => projectApi.publish(project.id))} disabled={busy}>{busy ? 'Working…' : 'Re-publish'}</NeuButton>
        </>
      ) : (
        <NeuButton variant="accent" onClick={() => void run(() => projectApi.publish(project.id))} disabled={busy}>{busy ? 'Publishing…' : 'Publish now'}</NeuButton>
      )}
    >
      {live && url && (
        <div className="dl-url">
          <span className="dl-url__dot" aria-hidden />
          <code title={url}>{url}</code>
          <button type="button" onClick={copy} aria-label={copied ? 'Copied' : 'Copy link'} title={copied ? 'Copied' : 'Copy link'}><AppIcon name={copied ? 'check' : 'copy'} /></button>
          <a href={url} target="_blank" rel="noreferrer" aria-label="Open the site in a new tab" title="Open in a new tab"><AppIcon name="openExternal" /></a>
        </div>
      )}
      {error && <NeuAlert>{error}</NeuAlert>}
      <p className="dl-note">Static hosting — serves your project's built files. Server-rendered apps are coming soon.</p>
    </NeuDialog>
  )
}

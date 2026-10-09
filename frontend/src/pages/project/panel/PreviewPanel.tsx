import { useEffect, useRef, useState } from 'react'
import { projectApi, type PreviewDraftEvent, type PreviewStatus, type Project } from '../../../api/projects'

/* ── Preview ──────────────────────────────────────────────────────────────── */
export function PreviewPanel({ project, update, draftUpdate, reloadRequest }: {
  project: Project | null; update: PreviewStatus | null; draftUpdate: PreviewDraftEvent | null; reloadRequest: number
}) {
  // The backend returns the full URL: previews live on a separate sites origin (never the API
  // origin), so the generated app cannot reach the API with the viewer's cookies.
  const [preview, setPreview] = useState<{ url: string; available: boolean; draft: { version: string; url: string } | null } | null>(null)
  const [status, setStatus] = useState<PreviewStatus | null>(null)
  const [previewError, setPreviewError] = useState(false)
  const [showPublished, setShowPublished] = useState(false)
  // Bumped to fetch a fresh URL (new token) and reload the frame: on a new build, or by hand.
  const [reloads, setReloads] = useState(0)
  const shownVersion = useRef<string | null>(null)
  const projectId = project?.id
  const live = project?.deploymentStatus === 'LIVE'

  useEffect(() => { setShowPublished(false) }, [projectId])
  useEffect(() => { if (reloadRequest > 0) setReloads((n) => n + 1) }, [reloadRequest])

  useEffect(() => {
    setPreviewError(false)
    if (!projectId) return
    let cancelled = false
    void projectApi.previewToken(projectId)
      .then((response) => {
        if (cancelled) return
        setPreview({ url: response.url, available: response.available, draft: response.draft })
        setStatus(response.status)
        shownVersion.current = response.status.version
      })
      .catch(() => { if (!cancelled) setPreviewError(true) })
    return () => { cancelled = true }
  }, [projectId, reloads])

  // A finished build with new content reloads the frame; building/failed only change the status line,
  // so the last good version stays visible meanwhile. While a draft is shown, the draft decides.
  const draftShown = useRef<string | null>(null)
  draftShown.current = preview?.draft?.version ?? null
  useEffect(() => {
    if (!update) return
    setStatus(update)
    if (update.state === 'READY' && update.version !== shownVersion.current && !draftShown.current) setReloads((n) => n + 1)
  }, [update])

  // A new draft while the agent works, or its end (back to the committed site): fetch the right URL.
  useEffect(() => {
    if (!draftUpdate) return
    if (draftUpdate.active ? draftUpdate.version !== draftShown.current : draftShown.current !== null) setReloads((n) => n + 1)
  }, [draftUpdate])

  const publishedView = live && showPublished
  const draft = publishedView ? null : preview?.draft ?? null
  const url = publishedView ? project?.publishedUrl : draft ? draft.url : preview?.available ? preview.url : null
  const building = status?.state === 'BUILDING'
  const failed = status?.state === 'FAILED'

  return (
    <div className="pw-preview">
      <div className="pw-preview-bar">
        {live && (
          <div className="pw-preview-switch" role="tablist" aria-label="Preview source">
            <button type="button" role="tab" aria-selected={!showPublished} className={!showPublished ? 'on' : ''} onClick={() => setShowPublished(false)}>Latest</button>
            <button type="button" role="tab" aria-selected={showPublished} className={showPublished ? 'on' : ''} onClick={() => setShowPublished(true)}>Published</button>
          </div>
        )}
        {draft && <span className="pw-preview-draft">Draft</span>}
        <span className={'pw-preview-state' + (failed && !publishedView && !draft ? ' failed' : '')} title={failed && !publishedView ? status?.error ?? undefined : undefined} aria-live="polite">
          {publishedView ? 'Published version'
            : draft ? 'Draft — Rendra is still working, not checked yet'
            : building ? 'Updating preview…'
              : failed ? (preview?.available ? 'Build failed — showing the last working version' : 'Build failed')
                : preview?.available ? 'Up to date' : ''}
        </span>
      </div>
      {url
        ? <iframe key={`${url}#${reloads}`} title="App preview" src={url} className="pw-frame" sandbox="allow-scripts allow-same-origin allow-forms" />
        : (
          <div className="pw-empty pw-dots">
            <div>
              <span className="pw-empty-icon">◐</span>
              <h3>{previewError ? 'Preview unavailable' : building ? 'Building preview' : failed ? 'Preview build failed' : 'No preview yet'}</h3>
              {failed && status?.error
                ? <pre className="pw-preview-error">{status.error}</pre>
                : <p>Your app’s live preview will appear here once it can run. For now, open the <b>Code</b> tab to see the files Rendra has written.</p>}
            </div>
          </div>
        )}
    </div>
  )
}

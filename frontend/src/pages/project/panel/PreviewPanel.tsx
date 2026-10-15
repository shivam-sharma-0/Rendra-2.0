import { useEffect, useRef, useState } from 'react'
import { projectApi, type PreviewDraftEvent, type PreviewStatus, type Project } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { NeuSegmentedControl } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import type { Device } from '../workspaceTypes'

const SOURCES = [{ value: 'latest', label: 'Latest' }, { value: 'published', label: 'Published' }] as const

/**
 * The running app in a frame sized for the chosen device (desktop fills the screen; tablet and mobile
 * are centred at their width), with a status line above it: build state, draft, latest/published.
 */
export function PreviewPanel({ project, update, draftUpdate, reloadRequest, device, onUrlChange }: {
  project: Project | null; update: PreviewStatus | null; draftUpdate: PreviewDraftEvent | null; reloadRequest: number
  device: Device
  /** Told the address being shown (null when none), e.g. for the header's "open in a new tab". */
  onUrlChange?: (url: string | null) => void
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
    let refreshTimer: ReturnType<typeof setTimeout> | undefined
    void projectApi.previewToken(projectId)
      .then((response) => {
        if (cancelled) return
        setPreview({ url: response.url, available: response.available, draft: response.draft })
        setStatus(response.status)
        shownVersion.current = response.status.version
        const expires = Math.min(Date.parse(response.expiresAt), response.draft ? Date.parse(response.draft.expiresAt) : Infinity)
        if (Number.isFinite(expires)) refreshTimer = setTimeout(() => setReloads(n => n + 1), Math.max(1000, expires - Date.now() - 30_000))
      })
      .catch(() => { if (!cancelled) setPreviewError(true) })
    return () => { cancelled = true; if (refreshTimer) clearTimeout(refreshTimer) }
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

  useEffect(() => {
    onUrlChange?.(url ?? null)
    return () => onUrlChange?.(null)
  }, [url, onUrlChange])
  const building = status?.state === 'BUILDING'
  const failed = status?.state === 'FAILED'

  const stateText = publishedView ? 'Published version'
    : draft ? 'Draft — Rendra is still working, not checked yet'
      : building ? 'Updating preview…'
        : failed ? (preview?.available ? 'Build failed — showing the last working version' : 'Build failed')
          : preview?.available ? 'Up to date' : ''

  return (
    <div className="pv-preview">
      <div className="pv-preview__bar">
        {live && (
          <NeuSegmentedControl className="pv-source" options={SOURCES} value={showPublished ? 'published' : 'latest'} onChange={(v) => setShowPublished(v === 'published')} aria-label="Preview source" />
        )}
        {draft && <span className="pv-chip pv-chip--wait">Draft</span>}
        <span className={cx('pv-preview__state', failed && !publishedView && !draft && 'is-failed')} title={failed && !publishedView ? status?.error ?? undefined : undefined} aria-live="polite">{stateText}</span>
      </div>
      {url
        ? (
          <div className={`pv-device pv-device--${device}`}>
            <iframe key={`${url}#${reloads}`} title="App preview" src={url} className="pv-frame" sandbox="allow-scripts allow-same-origin allow-forms" />
          </div>
        )
        : (
          <div className="pv-preview__empty">
            <span className="pv-preview__icon"><AppIcon name={failed ? 'warning' : 'preview'} size={20} /></span>
            <h3>{previewError ? 'Preview unavailable' : building ? 'Building preview' : failed ? 'Preview build failed' : 'No preview yet'}</h3>
            {failed && status?.error
              ? <pre className="pv-preview__error">{status.error}</pre>
              : <p>Your app’s live preview will appear here once it can run. For now, open <b>Code</b> to see the files Rendra has written.</p>}
          </div>
        )}
    </div>
  )
}

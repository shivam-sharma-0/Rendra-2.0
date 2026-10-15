import { useEffect, useRef, useState } from 'react'
import { agentApi, type AgentRun, type FailedDraft } from '../../../api/projects'
import { newAiMessageId, newUserMessageId } from '../../../lib/ids'
import { NeuButton } from '../../../components/neu'

/** Source is opt-in technical detail; never execute this unverified archive in the platform page. */
export function FailedDraftPanel({ projectId, runId, onRetried }: { projectId: string; runId: string; onRetried: (run: AgentRun) => void }) {
  const [draft, setDraft] = useState<FailedDraft | null>(null)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [content, setContent] = useState<string>()
  const [checkingSnapshot, setCheckingSnapshot] = useState(false)
  const fileRequest = useRef<AbortController | null>(null)
  // Preserve IDs across ambiguous POST failures. Clicking again cannot start a second build.
  const retryRequest = useRef({ id: newUserMessageId(), aiMessageId: newAiMessageId() })
  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    const until = Date.now() + 15_000
    setDraft(null); setError(undefined); setContent(undefined); setCheckingSnapshot(true)
    retryRequest.current = { id: newUserMessageId(), aiMessageId: newAiMessageId() }
    const load = async () => {
      try {
        const response = await agentApi.failedDraft(projectId, runId, { signal: controller.signal })
        if (controller.signal.aborted) return
        setDraft(response)
        if (response.state === 'UNAVAILABLE' && Date.now() < until) timer = setTimeout(() => { void load() }, 1000)
        else setCheckingSnapshot(false)
      } catch (e) {
        if (!controller.signal.aborted) { setCheckingSnapshot(false); setError(e instanceof Error ? e.message : 'Could not load this draft.') }
      }
    }
    void load()
    return () => { controller.abort(); if (timer) clearTimeout(timer); fileRequest.current?.abort() }
  }, [projectId, runId])
  const act = async (action: () => Promise<void>) => {
    setBusy(true); setError(undefined)
    try { await action() } catch (e) { setError(e instanceof Error ? e.message : 'Could not complete this action.') }
    finally { setBusy(false) }
  }
  const inspect = async (path: string) => {
    fileRequest.current?.abort()
    const controller = new AbortController(); fileRequest.current = controller; setContent(undefined); setError(undefined)
    try {
      const file = await agentApi.failedDraftFile(projectId, runId, path, { signal: controller.signal })
      if (!controller.signal.aborted) setContent(file.binary ? 'Binary file. Download the source archive to inspect it.' : `${file.content ?? ''}${file.truncated ? '\n\n[Preview shortened. Download for the full file.]' : ''}`)
    } catch (e) { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : 'Could not read this file.') }
  }
  const download = () => act(async () => {
    const blob = await agentApi.downloadFailedDraft(projectId, runId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `unverified-build-${runId}.zip`; anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
  const retry = () => act(async () => {
    const run = await agentApi.retryFailedDraft(projectId, runId, retryRequest.current)
    setDraft((current) => current ? { ...current, retryRunId: run.id } : current); onRetried(run)
  })
  return <section className="pv-failed-draft" aria-label="Failed build draft">
    <b>Unverified draft</b>
    <p>This build did not pass the checks. Its source can be inspected or downloaded. Retry checks it again before saving.</p>
    {error && <p role="alert">{error}</p>}
    {!draft && !error && <p role="status">Loading draft…</p>}
    {draft && !draft.available && <p role="status">{checkingSnapshot ? 'Checking the stopped build snapshot…' : draft.state === 'EXPIRED' ? 'This source snapshot has expired.' : 'Source inspection is not available for this run.'}</p>}
    {draft?.available && <>
      <div className="pv-failed-draft__actions">
        <NeuButton size="sm" disabled={busy} onClick={download}>Download source</NeuButton>
        <NeuButton size="sm" disabled={busy || !!draft.retryRunId} onClick={retry}>{draft.retryRunId ? 'Retry already started' : busy ? 'Working…' : 'Retry build'}</NeuButton>
      </div>
      {draft.files.length === 0 && <p>This draft removed the remaining source files. The removals are retained for retry.</p>}
      <label>Inspect source <select defaultValue="" disabled={draft.files.length === 0} onChange={(e) => { if (e.target.value) void inspect(e.target.value) }}>
        <option value="" disabled>Choose a file</option>
        {draft.files.map((file) => <option key={file.path} value={file.path}>{file.path}</option>)}
      </select></label>
      {content !== undefined && <pre className="pv-failed-draft__source">{content}</pre>}
    </>}
  </section>
}

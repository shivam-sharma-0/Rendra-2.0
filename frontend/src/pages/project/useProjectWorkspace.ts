import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { agentApi, messageApi, projectApi, type AgentRun, type AgentStep, type Message, type MessageFeedback, type PreviewDraftEvent, type PreviewStatus, type Project, type ProjectType } from '../../api/projects'
import { qk } from '../../api/queries'
import { type RealtimeMessage, useRealtime } from '../../realtime/useRealtime'
import { isActive } from './runUtils'
import { PAGE_SIZE, TIMELINE_EVENT_TYPES, type TimelineEvent } from './workspaceTypes'

/**
 * Everything the workspace knows about one project: the project, its conversation (paged, newest
 * first), its agent runs and their steps, live preview status, and the actions on them (send,
 * feedback, stop, restore). Kept current by the project's realtime channel.
 */
export function useProjectWorkspace(id: string) {
  const [project, setProject] = useState<Project | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  // Latest preview build state pushed by the backend (PREVIEW_STATUS); the preview reloads on a new version.
  const [previewUpdate, setPreviewUpdate] = useState<PreviewStatus | null>(null)
  const [draftUpdate, setDraftUpdate] = useState<PreviewDraftEvent | null>(null)
  useEffect(() => { setPreviewUpdate(null); setDraftUpdate(null) }, [id])
  const [runs, setRuns] = useState<AgentRun[]>([])
  const [stepsByRun, setStepsByRun] = useState<Record<string, AgentStep[]>>({})
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [rollbackError, setRollbackError] = useState<string>()
  const [rollingBack, setRollingBack] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [rollingBackId, setRollingBackId] = useState<string | null>(null)
  const [oldestPage, setOldestPage] = useState(0)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const queryClient = useQueryClient()
  // Highest step seq already held per run, so the timeline only fetches what it is missing (afterSeq).
  const maxSeqRef = useRef<Record<string, number>>({})
  const messageRefreshVersion = useRef(0)

  const selectedRun = useMemo(() => runs.find((run) => isActive(run)) ?? runs[0], [runs])
  const steps = selectedRun ? stepsByRun[selectedRun.id] ?? [] : []
  const hasOlder = oldestPage > 0

  const mergeSteps = useCallback((runId: string, incoming: AgentStep[]) => {
    setStepsByRun((previous) => {
      const bySeq = new Map((previous[runId] ?? []).map((step) => [step.seq, step]))
      for (const step of incoming) bySeq.set(step.seq, step)
      const merged = [...bySeq.values()].sort((a, b) => a.seq - b.seq)
      maxSeqRef.current[runId] = merged.length ? merged[merged.length - 1].seq : -1
      return { ...previous, [runId]: merged }
    })
  }, [])

  const refreshTimeline = useCallback(async () => {
    const nextRuns = await agentApi.list(id)
    const selected = nextRuns.find((run) => isActive(run)) ?? nextRuns[0]
    setRuns(nextRuns)
    if (!selected) return
    const afterSeq = maxSeqRef.current[selected.id] ?? -1
    const next = await agentApi.steps(id, selected.id, afterSeq)
    if (next.length) mergeSteps(selected.id, next)
  }, [id, mergeSteps])

  /** Fetch the newest window of messages (the last page, so recent messages are never hidden). */
  const loadLatestMessages = useCallback(async (): Promise<Message[]> => {
    const first = await messageApi.list(id, 0, PAGE_SIZE)
    const page = first.last || first.totalPages <= 1 ? first : await messageApi.list(id, first.totalPages - 1, PAGE_SIZE)
    setOldestPage(page.number)
    return page.content
  }, [id])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setNotFound(false)
    setRollbackError(undefined)
    maxSeqRef.current = {}
    ;(async () => {
      try {
        const [nextProject, content, nextRuns] = await Promise.all([projectApi.get(id), loadLatestMessages(), agentApi.list(id)])
        const selected = nextRuns.find((run) => isActive(run)) ?? nextRuns[0]
        const runSteps = selected ? await agentApi.steps(id, selected.id) : []
        if (cancelled) return
        setProject(nextProject)
        setMessages(content)
        setRuns(nextRuns)
        if (selected && runSteps.length) {
          maxSeqRef.current[selected.id] = runSteps[runSteps.length - 1].seq
          setStepsByRun({ [selected.id]: runSteps })
        } else {
          setStepsByRun({})
        }
      } catch {
        if (!cancelled) setNotFound(true)
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true; messageRefreshVersion.current += 1 }
  }, [id, loadLatestMessages])

  const upsert = useCallback((incoming: Message) => {
    setMessages((previous) => {
      const index = previous.findIndex((message) => message.id === incoming.id)
      if (index === -1) return [...previous, incoming]
      const next = previous.slice()
      next[index] = { ...incoming, createdAt: incoming.createdAt ?? previous[index].createdAt }
      return next
    })
  }, [])

  const refreshMessages = useCallback(() => {
    const version = ++messageRefreshVersion.current
    void loadLatestMessages().then((incoming) => {
      // A later message or request invalidates an older permission/offer snapshot.
      if (version === messageRefreshVersion.current) incoming.forEach(upsert)
    }).catch(() => undefined)
  }, [loadLatestMessages, upsert])

  const addTimelineStep = useCallback((event: TimelineEvent, type: string) => {
    if (!event.runId || typeof event.seq !== 'number') return
    const step: AgentStep = {
      seq: event.seq,
      type: type as AgentStep['type'],
      content: event.content ?? null,
      data: event.data === undefined || event.data === null ? null : JSON.stringify(event.data),
      createdAt: new Date().toISOString(),
      activity: event.activity ?? null,
    }
    mergeSteps(event.runId, [step])
  }, [mergeSteps])

  const onRealtime = useCallback((message: RealtimeMessage) => {
    if (message.type === 'CONNECTED') {
      refreshMessages()
      void refreshTimeline().catch(() => undefined)
      return
    }
    if (message.type === 'PREVIEW_DRAFT') {
      const update = message.payload as (PreviewDraftEvent & { projectId?: string }) | undefined
      if (update?.projectId === id) setDraftUpdate({ runId: update.runId, version: update.version, active: update.active })
      return
    }
    if (message.type === 'PREVIEW_STATUS') {
      const update = message.payload as (PreviewStatus & { projectId?: string }) | undefined
      if (update?.projectId === id) setPreviewUpdate({ state: update.state, version: update.version, error: update.error })
      return
    }
    if (TIMELINE_EVENT_TYPES.has(message.type)) {
      const timeline = message.payload as TimelineEvent | undefined
      addTimelineStep(timeline ?? {}, message.type)
      if (message.type === 'STATUS') void refreshTimeline().catch(() => undefined)
      return
    }
    const payload = message.payload as (Partial<Message> & { id?: string; delta?: string }) | undefined
    if (!payload?.id || payload.projectId !== id) return
    messageRefreshVersion.current += 1
    if (message.type === 'MESSAGE_DELTA' && typeof payload.delta === 'string') {
      const delta = payload.delta
      const messageId = payload.id
      setMessages((previous) => {
        const index = previous.findIndex((item) => item.id === messageId)
        if (index === -1) {
          const now = new Date().toISOString()
          const created: Message = {
            id: messageId, projectId: id, role: 'ASSISTANT', content: delta, status: 'STREAMING',
            mode: null, replyToId: null, feedback: null, seq: Number.MAX_SAFE_INTEGER, createdAt: now, updatedAt: now,
          }
          return [...previous, created]
        }
        const next = previous.slice()
        next[index] = { ...next[index], content: next[index].content + delta, status: 'STREAMING' }
        return next
      })
    } else if (message.type === 'MESSAGE_UPSERT') {
      upsert(payload as Message)
      if (payload.status === 'COMPLETE' || payload.status === 'FAILED') {
        // Room payloads cannot carry actor-specific build permission. Fetch the scoped offer after completion.
        refreshMessages()
        void refreshTimeline().catch(() => undefined)
        // Covers both the file tree and every open file's content query (prefix match) — a build may
        // have changed either, so re-fetch on next access instead of trusting stale cached files.
        void queryClient.invalidateQueries({ queryKey: qk.fileTree(id) })
        void queryClient.invalidateQueries({ queryKey: qk.analytics(id) })
        void queryClient.invalidateQueries({ queryKey: qk.dailyUsage })
      }
    }
  }, [addTimelineStep, id, refreshMessages, refreshTimeline, upsert, queryClient])

  useRealtime({ enabled: !!project, projectId: id, onMessage: onRealtime })

  const loadOlder = useCallback(async () => {
    if (oldestPage <= 0 || loadingOlder) return
    setLoadingOlder(true)
    try {
      const older = await messageApi.list(id, oldestPage - 1, PAGE_SIZE)
      setMessages((current) => {
        const known = new Set(current.map((message) => message.id))
        return [...older.content.filter((message) => !known.has(message.id)), ...current]
      })
      setOldestPage(older.number)
    } catch {
      // leave the conversation as-is; the user can retry
    } finally {
      setLoadingOlder(false)
    }
  }, [id, oldestPage, loadingOlder])

  // A nonce (not just the text) so editing the same message twice in a row still re-triggers the
  // composer's "load this in" effect, even when the text hasn't changed since the first click.
  const [editRequest, setEditRequest] = useState<{ text: string; nonce: number } | null>(null)
  const requestEdit = useCallback((text: string) => setEditRequest({ text, nonce: Date.now() }), [])
  const [sendError, setSendError] = useState<string>()

  const send = useCallback(async (prompt: string, mode: ProjectType, files: File[] = [], attachmentPaths: string[] = [], proposalId?: string) => {
    messageRefreshVersion.current += 1
    setSendError(undefined)
    try {
      const uploaded = files.length ? await projectApi.uploadAttachments(id, files) : []
      const paths = [...attachmentPaths, ...uploaded.map((file) => file.path)]
      const created = await messageApi.post(id, prompt, mode, paths, proposalId)
      // The agent can finish (and its realtime updates can land) before this response does, since
      // ids are client-generated and shared between this response and the realtime stream. Skip any
      // id already in state instead of blindly appending, or a fresher realtime row gets a stale,
      // never-updated duplicate placed right after it (permanently stuck mid-stream).
      setMessages((previous) => {
        const known = new Set(previous.map((m) => m.id))
        return [...previous, ...created.filter((m) => !known.has(m.id))]
      })
      window.setTimeout(() => { void refreshTimeline().catch(() => undefined) }, 350)
    } catch (error) {
      // The suggestion owns this error. A failed click must not replace the user's draft.
      if (proposalId) throw error
      // Nothing was saved (e.g. a usage limit): say why, and put the text back so it can be resent.
      setSendError(error instanceof Error ? error.message : 'Could not send your message. Please try again.')
      requestEdit(prompt)
    }
  }, [id, refreshTimeline, requestEdit])

  const setFeedback = useCallback(async (messageId: string, feedback: MessageFeedback | null) => {
    setMessages((previous) => previous.map((m) => (m.id === messageId ? { ...m, feedback } : m)))
    try {
      await messageApi.feedback(id, messageId, feedback)
    } catch {
      // best-effort; a later realtime MESSAGE_UPSERT or reload reconciles the true state
    }
  }, [id])

  const cancel = useCallback(async () => {
    if (!selectedRun || selectedRun.status === 'COMMITTING') return
    setCancelling(true)
    try {
      await agentApi.cancel(id, selectedRun.id)
      await refreshTimeline()
    } catch {
      // ignore — the run may have just finished; the timeline refresh reflects the real state
    } finally {
      await refreshTimeline()
      setCancelling(false)
    }
  }, [id, refreshTimeline, selectedRun])

  const rollback = useCallback(async () => {
    if (!selectedRun) return
    setRollingBack(true)
    setRollbackError(undefined)
    try {
      await agentApi.rollback(id, selectedRun.id)
      await refreshTimeline()
      void queryClient.invalidateQueries({ queryKey: qk.fileTree(id) })
    } catch (error) {
      setRollbackError(error instanceof Error ? error.message : 'Could not restore this build.')
    } finally {
      setRollingBack(false)
    }
  }, [id, refreshTimeline, selectedRun, queryClient])

  // Per-run restore for the version-history panel (backend only permits the latest completed build).
  const rollbackRun = useCallback(async (runId: string) => {
    setRollingBackId(runId)
    setRollbackError(undefined)
    try {
      await agentApi.rollback(id, runId)
      await refreshTimeline()
      void queryClient.invalidateQueries({ queryKey: qk.fileTree(id) })
    } catch (error) {
      setRollbackError(error instanceof Error ? error.message : 'Could not restore this build.')
    } finally {
      setRollingBackId(null)
    }
  }, [id, refreshTimeline, queryClient])
  return {
    acceptRetriedRun: (run: AgentRun) => { setRuns((current) => [run, ...current.filter((item) => item.id !== run.id)]); void loadLatestMessages().then(setMessages).catch(() => { /* realtime/polling recovers message updates */ }) },
    project, setProject, messages, previewUpdate, draftUpdate,
    runs, stepsByRun, selectedRun, steps, loading, notFound,
    hasOlder, loadingOlder, loadOlder,
    editRequest, requestEdit, sendError, send, setFeedback,
    cancel, cancelling, rollback, rollingBack, rollbackRun, rollingBackId, rollbackError,
  }
}

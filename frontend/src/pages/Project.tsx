import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { Link, useParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  agentApi, messageApi, projectApi, requirementsApi,
  type AgentRun, type AgentStep, type FileNode, type Message, type MessageFeedback, type MessageRole, type StagedFile,
  type Project, type ProjectType, type ProjectRole, type RequirementSession,
} from '../api/projects'
import {
  qk, useProjectMembers, useShareProject, useUpdateProjectMemberRole, useRemoveProjectMember,
  useAnalytics, useSecurityScan, useFileTree, useFileContent, useStagedFiles,
  useDatabase,
} from '../api/queries'
import { EmptyState, ErrorState, LoadingState } from '../components/ui/States'
import { useRealtime, type RealtimeMessage } from '../realtime/useRealtime'
import AppShell from '../kiln/AppShell'
import { Ico } from '../kiln/parts'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const TIMELINE_EVENT_TYPES = new Set(['THINKING', 'TOOL_CALL', 'TOOL_RESULT', 'ASK_USER', 'MESSAGE', 'STATUS'])
const PAGE_SIZE = 50

const BRAIN_ICON = 'M9.5 2A2.5 2.5 0 0 1 12 4.5v15a2.5 2.5 0 0 1-4.96-.44 2.5 2.5 0 0 1-2.96-3.08 3 3 0 0 1-.34-5.58 2.5 2.5 0 0 1 1.32-4.24 2.5 2.5 0 0 1 1.98-3A2.5 2.5 0 0 1 9.5 2ZM14.5 2A2.5 2.5 0 0 0 12 4.5v15a2.5 2.5 0 0 0 4.96-.44 2.5 2.5 0 0 0 2.96-3.08 3 3 0 0 0 .34-5.58 2.5 2.5 0 0 0-1.32-4.24 2.5 2.5 0 0 0-1.98-3A2.5 2.5 0 0 0 14.5 2Z'

const WS_MAIN_TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'preview', label: 'Preview', icon: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z' },
  { id: 'files',   label: 'Files',   icon: 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z' },
  { id: 'code',    label: 'Code',    icon: 'M16 18l6-6-6-6M8 6l-6 6 6 6' },
  { id: 'details', label: 'Details', icon: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01' },
]

const WS_MORE_TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'history',   label: 'History',   icon: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2' },
  { id: 'analytics', label: 'Analytics', icon: 'M3 3v18h18M18 17l-5-5-4 4-3-3' },
  { id: 'security',  label: 'Security',  icon: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z' },
  { id: 'database',  label: 'Database',  icon: 'M12 2C8.7 2 6 3.8 6 6s2.7 4 6 4 6-1.8 6-4-2.7-4-6-4zM6 10v4c0 2.2 2.7 4 6 4s6-1.8 6-4v-4M6 14v4c0 2.2 2.7 4 6 4s6-1.8 6-4v-4' },
]

const WS_DEVICES: { id: string; label: string; icon: string }[] = [
  { id: 'desktop', label: 'Desktop', icon: 'M20 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2zM12 17v4M8 21h8' },
  { id: 'tablet',  label: 'Tablet',  icon: 'M18 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM12 18h.01' },
  { id: 'mobile',  label: 'Mobile',  icon: 'M12 18h.01M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z' },
]

const PANEL_TITLES: Partial<Record<Tab, string>> = {
  files: 'Files', code: 'Code', details: 'Details', history: 'History',
  analytics: 'Analytics', security: 'Security', database: 'Database', thinking: 'Reasoning',
}

type TimelineEvent = { runId?: string; seq?: number; content?: string; data?: unknown }
type Tab = 'preview' | 'files' | 'code' | 'history' | 'details' | 'analytics' | 'security' | 'database' | 'thinking'
type LayoutMode = 'split' | 'chat-only' | 'preview-only'

const DEFAULT_CHAT_WIDTH = 440
const MIN_CHAT_WIDTH = 360
const MIN_PANEL_WIDTH = 340
const COLLAPSE_THRESHOLD = 140

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function readStoredLayoutMode(): LayoutMode {
  try {
    const value = localStorage.getItem('pw-layout-mode')
    return value === 'chat-only' || value === 'preview-only' || value === 'split' ? value : 'split'
  } catch { return 'split' }
}

function readStoredChatWidth(): number {
  try {
    const value = Number(localStorage.getItem('pw-chat-width'))
    return Number.isFinite(value) && value > 0 ? value : DEFAULT_CHAT_WIDTH
  } catch { return DEFAULT_CHAT_WIDTH }
}

export default function ProjectPage() {
  const { id = '' } = useParams()
  const [project, setProject] = useState<Project | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
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
  const [tab, setTab] = useState<Tab>('code')
  const [thinkingPanel, setThinkingPanel] = useState<{ reasoning: string; seconds: number } | null>(null)
  const [shareOpen, setShareOpen] = useState(false)
  const [publishOpen, setPublishOpen] = useState(false)
  const [requirements, setRequirements] = useState<RequirementSession | null>(null)
  const [requirementsBusy, setRequirementsBusy] = useState(false)
  const [requirementsError, setRequirementsError] = useState('')
  const requirementsStarted = useRef<string | null>(null)
  const queryClient = useQueryClient()
  const [layoutMode, setLayoutMode] = useState<LayoutMode>(readStoredLayoutMode)
  const [chatWidth, setChatWidth] = useState<number>(readStoredChatWidth)
  const [dragging, setDragging] = useState(false)
  const [device, setDevice] = useState<'desktop' | 'tablet' | 'mobile'>('desktop')
  const [moreOpen, setMoreOpen] = useState(false)
  const [morePos, setMorePos] = useState<{ top: number; left: number } | null>(null)
  const moreRef = useRef<HTMLButtonElement>(null)
  const [panelCompact, setPanelCompact] = useState(false)
  const panelRef = useRef<HTMLElement>(null)
  const [quote, setQuote] = useState<Quote | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const splitRef = useRef<HTMLDivElement>(null)
  const chatInnerRef = useRef<HTMLDivElement>(null)
  // Highest step seq already held per run, so the timeline only fetches what it is missing (afterSeq).
  const maxSeqRef = useRef<Record<string, number>>({})

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
    return () => { cancelled = true }
  }, [id, loadLatestMessages])

  useEffect(() => {
    if (!project || project.lifecycleStage !== 'REQUIREMENTS' || !needsRequirementInterview(project) || requirementsStarted.current === id) return
    requirementsStarted.current = id
    setRequirementsError('')
    void requirementsApi.start(id).then(setRequirements).catch((error) => {
      setRequirementsError(error instanceof Error ? error.message : 'Could not start requirements interview.')
    })
  }, [id, project])

  useEffect(() => {
    if (project?.lifecycleStage === 'REQUIREMENTS' && needsRequirementInterview(project)) {
      setTab('details')
      setLayoutMode('chat-only')
    }
  }, [project?.lifecycleStage])

  const answerRequirement = useCallback(async (questionId: string, selectedOption: string | null, otherText: string | null) => {
    setRequirementsBusy(true)
    setRequirementsError('')
    try {
      setRequirements(await requirementsApi.answer(id, questionId, { selectedOption, otherText }))
    } catch (error) {
      setRequirementsError(error instanceof Error ? error.message : 'Could not save this answer.')
      throw error
    } finally {
      setRequirementsBusy(false)
    }
  }, [id])

  const approveRequirements = useCallback(async () => {
    setRequirementsBusy(true)
    setRequirementsError('')
    try {
      setRequirements(await requirementsApi.approve(id))
      setProject((current) => current ? { ...current, lifecycleStage: 'DESIGN' } : current)
      setLayoutMode('split')
      setTab('details')
    } catch (error) {
      setRequirementsError(error instanceof Error ? error.message : 'Could not approve requirements.')
    } finally {
      setRequirementsBusy(false)
    }
  }, [id])

  const skipRequirements = useCallback(async () => {
    if (!requirements) return
    setRequirementsBusy(true)
    setRequirementsError('')
    try {
      let latest = requirements
      for (const question of requirements.questions) {
        if (!question.required || question.selectedOption || question.otherText) continue
        const recommended = question.recommendedOption
          ?? question.options.find((option) => option.recommended)?.id
          ?? question.options[0]?.id
        if (!recommended) continue
        latest = await requirementsApi.answer(id, question.id, { selectedOption: recommended, otherText: null })
      }
      setRequirements(latest)
      if (latest.complete) {
        const approved = await requirementsApi.approve(id)
        setRequirements(approved)
        setProject((current) => current ? { ...current, lifecycleStage: 'DESIGN' } : current)
        setLayoutMode('split')
        setTab('details')
      }
    } catch (error) {
      setRequirementsError(error instanceof Error ? error.message : 'Could not use the recommended answers.')
    } finally {
      setRequirementsBusy(false)
    }
  }, [id, requirements])

  const upsert = useCallback((incoming: Message) => {
    setMessages((previous) => {
      const index = previous.findIndex((message) => message.id === incoming.id)
      if (index === -1) return [...previous, incoming]
      const next = previous.slice()
      next[index] = incoming
      return next
    })
  }, [])

  const addTimelineStep = useCallback((event: TimelineEvent, type: string) => {
    if (!event.runId || typeof event.seq !== 'number') return
    const step: AgentStep = {
      seq: event.seq,
      type: type as AgentStep['type'],
      content: event.content ?? null,
      data: event.data === undefined ? null : JSON.stringify(event.data),
      createdAt: new Date().toISOString(),
    }
    mergeSteps(event.runId, [step])
  }, [mergeSteps])

  const onRealtime = useCallback((message: RealtimeMessage) => {
    if (message.type === 'CONNECTED') {
      void loadLatestMessages().then(setMessages).catch(() => undefined)
      void refreshTimeline().catch(() => undefined)
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
        void refreshTimeline().catch(() => undefined)
        // Covers both the file tree and every open file's content query (prefix match) — a build may
        // have changed either, so re-fetch on next access instead of trusting stale cached files.
        void queryClient.invalidateQueries({ queryKey: qk.fileTree(id) })
      }
    }
  }, [addTimelineStep, id, loadLatestMessages, refreshTimeline, upsert, queryClient])

  useRealtime({ enabled: !!project, projectId: id, onMessage: onRealtime })

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])

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

  const send = useCallback(async (prompt: string, mode: ProjectType, files: File[] = []) => {
    try {
      if (files.length) await projectApi.uploadAttachments(id, files)
      const created = await messageApi.post(id, prompt, mode)
      // The agent can finish (and its realtime updates can land) before this response does, since
      // ids are client-generated and shared between this response and the realtime stream. Skip any
      // id already in state instead of blindly appending, or a fresher realtime row gets a stale,
      // never-updated duplicate placed right after it (permanently stuck mid-stream).
      setMessages((previous) => {
        const known = new Set(previous.map((m) => m.id))
        return [...previous, ...created.filter((m) => !known.has(m.id))]
      })
      window.setTimeout(() => { void refreshTimeline().catch(() => undefined) }, 350)
    } catch {
      // Keep the composer usable. The API error is not destructive and can be retried by the user.
    }
  }, [id, refreshTimeline])

  // A nonce (not just the text) so editing the same message twice in a row still re-triggers the
  // composer's "load this in" effect, even when the text hasn't changed since the first click.
  const [editRequest, setEditRequest] = useState<{ text: string; nonce: number } | null>(null)
  const requestEdit = useCallback((text: string) => setEditRequest({ text, nonce: Date.now() }), [])

  const setFeedback = useCallback(async (messageId: string, feedback: MessageFeedback | null) => {
    setMessages((previous) => previous.map((m) => (m.id === messageId ? { ...m, feedback } : m)))
    try {
      await messageApi.feedback(id, messageId, feedback)
    } catch {
      // best-effort; a later realtime MESSAGE_UPSERT or reload reconciles the true state
    }
  }, [id])

  const cancel = useCallback(async () => {
    if (!selectedRun) return
    setCancelling(true)
    try {
      await agentApi.cancel(id, selectedRun.id)
      await refreshTimeline()
    } catch {
      // ignore — the run may have just finished; the timeline refresh reflects the real state
    } finally {
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

  useEffect(() => {
    try { localStorage.setItem('pw-layout-mode', layoutMode) } catch { /* ignore */ }
  }, [layoutMode])
  useEffect(() => {
    try { localStorage.setItem('pw-chat-width', String(chatWidth)) } catch { /* ignore */ }
  }, [chatWidth])

  useEffect(() => {
    const el = panelRef.current
    if (!el) return
    const ro = new ResizeObserver(([entry]) => setPanelCompact(entry.contentRect.width < 460))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const resizePointerMove = useCallback((e: React.PointerEvent) => {
    if (!e.buttons) return
    const rect = splitRef.current?.getBoundingClientRect()
    if (!rect) return
    const raw = e.clientX - rect.left
    setChatWidth(clamp(raw, MIN_CHAT_WIDTH, rect.width - MIN_PANEL_WIDTH))
  }, [])

  const resizePointerUp = useCallback((e: React.PointerEvent) => {
    setDragging(false)
    const rect = splitRef.current?.getBoundingClientRect()
    if (!rect) return
    const raw = e.clientX - rect.left
    if (raw < COLLAPSE_THRESHOLD) { setLayoutMode('preview-only'); return }
    if (raw > rect.width - COLLAPSE_THRESHOLD) { setLayoutMode('chat-only'); return }
    setLayoutMode('split')
    setChatWidth(clamp(raw, MIN_CHAT_WIDTH, rect.width - MIN_PANEL_WIDTH))
  }, [])

  const resizePointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    setDragging(true)
    e.currentTarget.setPointerCapture(e.pointerId)
  }, [])

  if (notFound) {
    return (
      <AppShell active="chats">
        <div className="pw-missing">
          <div>
            <p>This project does not exist or is not available to you.</p>
            <Link to="/dashboard">← Back to dashboard</Link>
          </div>
        </div>
      </AppShell>
    )
  }

  const runActive = !!selectedRun && isActive(selectedRun)
  const canRollback = !!selectedRun && selectedRun.mode === 'BUILD' && selectedRun.rollbackAvailable && !runActive

  const chatVisible = layoutMode !== 'preview-only'
  const panelVisible = layoutMode !== 'chat-only'

  return (
    <AppShell active="chats">
      <div className={'pw w-full h-full flex flex-row bg-[var(--bg)] text-[var(--ink)] font-[Inter,Geist,system-ui,-apple-system,sans-serif] text-base animate-[pw-fade_.26s_ease_both] motion-reduce:animate-none' + (dragging ? ' dragging' : '')} ref={splitRef}>
        {shareOpen && <ShareDialog projectId={id} onClose={() => setShareOpen(false)} />}
        {publishOpen && project && <PublishDialog project={project} onClose={() => setPublishOpen(false)} onChange={setProject} />}
        {project?.lifecycleStage === 'REQUIREMENTS' && needsRequirementInterview(project) && requirements && (
          <RequirementDialog
            session={requirements}
            busy={requirementsBusy}
            error={requirementsError}
            onAnswer={answerRequirement}
            onApprove={approveRequirements}
            onSkipAll={skipRequirements}
          />
        )}

        <section className={'pw-chat' + (!chatVisible ? ' pw-chat--hidden' : '')} style={{
          width: layoutMode === 'split' ? chatWidth : undefined,
          flex: layoutMode === 'chat-only' ? '1 1 auto' : undefined,
          transition: dragging ? 'none' : 'flex-basis .3s cubic-bezier(.16,1,.3,1), width .18s ease, opacity .25s ease',
        }}>
          <header className="pw-chat-head">
            <button type="button" className="pw-project">
              <span>{project?.name ?? 'Loading…'}</span>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="6 9 12 15 18 9" />
              </svg>
            </button>
            <span className="pw-spacer" />
            <div style={{display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 500, color: 'var(--ok)'}}>
              <span className="pw-livedot" /> 1 online
            </div>
            <button
              className="pw-panelbtn"
              onClick={() => setLayoutMode(layoutMode === 'chat-only' ? 'split' : 'chat-only')}
              title={layoutMode === 'chat-only' ? 'Show preview' : 'Focus chat'}
            >
              {/* chat-only → show panel: right-pointing. split → hide panel: left-pointing */}
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                {layoutMode === 'chat-only'
                  ? <path d="M9 18l6-6-6-6" />
                  : <path d="M15 18l-6-6 6-6" />}
              </svg>
            </button>
          </header>
          <SelectionReplyPopup containerRef={chatInnerRef} onQuote={setQuote} />
          <div className="pw-chat-scroll">
            <div className="pw-chat-inner" ref={chatInnerRef}>
              {!loading && hasOlder && (
                <button className="pw-older" onClick={() => void loadOlder()} disabled={loadingOlder}>
                  {loadingOlder ? 'Loading…' : 'Load older messages'}
                </button>
              )}
              {loading
                ? <p className="pw-loading">Loading conversation…</p>
                : (() => {
                    const nonSystem = messages.filter((m) => m.role !== 'SYSTEM' && !isInternalWorkflowMessage(m))
                    // Sort user messages by server seq (monotonic insertion order) — the canonical
                    // order the backend uses. createdAt alone is unstable: messages can share the same
                    // @CreationTimestamp instant, so sorting on it inverts newly-sent messages until a
                    // refetch. Fall back to createdAt only if seq is somehow missing.
                    const userMsgs = nonSystem
                      .filter((m) => m.role === 'USER')
                      .sort((a, b) => (a.seq - b.seq) || (new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()))
                    // Map assistant messages by replyToId for O(1) lookup
                    const byReply = new Map<string, Message>()
                    nonSystem.filter((m) => m.role === 'ASSISTANT' && m.replyToId).forEach((m) => byReply.set(m.replyToId!, m))
                    // Build ordered list: user → its assistant reply, in order of user timestamps
                    const ordered: Message[] = []
                    for (const u of userMsgs) {
                      ordered.push(u)
                      const reply = byReply.get(u.id)
                      if (reply) ordered.push(reply)
                    }
                    // Orphaned assistant messages (streaming / replyToId not yet set)
                    const placed = new Set(ordered.map((m) => m.id))
                    nonSystem.filter((m) => m.role === 'ASSISTANT' && !placed.has(m.id)).forEach((m) => ordered.push(m))

                    return ordered.map((m) => {
                      const msgRun = m.role === 'ASSISTANT' ? runs.find((r) => r.messageId === m.id) : undefined
                      const msgSteps = msgRun ? stepsByRun[msgRun.id] ?? [] : []
                      return (
                        <ChatMessage key={m.id} message={m} run={msgRun} steps={msgSteps}
                          onOpenDetails={() => setTab('details')} onOpenPreview={() => setTab('preview')}
                          onOpenThinking={(reasoning, seconds) => {
                            setThinkingPanel({ reasoning, seconds })
                            setTab('thinking')
                            if (layoutMode === 'chat-only') setLayoutMode('split')
                          }}
                          onFeedback={(feedback) => void setFeedback(m.id, feedback)} onEdit={requestEdit} />
                      )
                    })
                  })()}
              {rollbackError && <p className="pw-error">{rollbackError}</p>}
              {requirements?.complete && <RequirementAnswerCard projectName={project?.name} session={requirements} onPreview={() => setTab('preview')} />}
              {project?.lifecycleStage === 'REQUIREMENTS' && needsRequirementInterview(project) && <RequirementWaiting />}
              <div ref={bottomRef} />
            </div>
          </div>
          <Composer defaultMode={project?.type ?? 'BUILD'} onSend={send} quote={quote} onClearQuote={() => setQuote(null)}
            editRequest={editRequest} />
        </section>

        <div
          className={
            'pw-resizer' +
            (dragging ? ' dragging' : '') +
            (!panelVisible ? ' pw-resizer--collapsed' : '') +
            (!chatVisible ? ' pw-resizer--chat-collapsed' : '')
          }
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize chat panel"
          onPointerDown={(e) => {
            if (!panelVisible || !chatVisible) setLayoutMode('split')
            resizePointerDown(e)
          }}
          onPointerMove={resizePointerMove}
          onPointerUp={resizePointerUp}
          onClick={() => { if (!panelVisible || !chatVisible) setLayoutMode('split') }}
        >
          {layoutMode === 'split' && (
            <div className="pw-resizer-hint">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><circle cx="9" cy="7" r="1.5"/><circle cx="15" cy="7" r="1.5"/><circle cx="9" cy="12" r="1.5"/><circle cx="15" cy="12" r="1.5"/><circle cx="9" cy="17" r="1.5"/><circle cx="15" cy="17" r="1.5"/></svg>
              Drag to resize · click to focus
            </div>
          )}
          {!panelVisible && (
            <div className="pw-resizer-edge-hint">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 18l6-6-6-6" /></svg>
              Open panel
            </div>
          )}
          {!chatVisible && (
            <div className="pw-resizer-edge-hint pw-resizer-edge-hint--left">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M15 18l-6-6 6-6" /></svg>
              Open chat
            </div>
          )}
        </div>

        <section ref={panelRef} className={'pw-panel' + (tab === 'code' ? ' code' : '') + (!panelVisible ? ' pw-panel--hidden' : '') + (panelCompact ? ' pw-panel--compact' : '')}>
          <div className="pw-tabs">
            {/* Tab group: compact = only active tab + "..." for all others */}
            <div className="pw-tab-grp">
              {panelCompact ? (
                (() => {
                  const allTabs = [...WS_MAIN_TABS, ...WS_MORE_TABS]
                  const activeT = allTabs.find(t => t.id === tab || (tab === 'thinking' && t.id === 'details'))
                  return activeT ? (
                    <button key={activeT.id} type="button" className="pw-tab active"
                      onClick={() => { setTab(activeT.id); setMoreOpen(false) }} aria-label={activeT.label}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={activeT.icon} /></svg>
                      <span className="pw-tab-label">{activeT.label}</span>
                    </button>
                  ) : null
                })()
              ) : (
                WS_MAIN_TABS.map(t => {
                  const isActive = tab === t.id || (tab === 'thinking' && t.id === 'details')
                  return (
                    <button key={t.id} type="button" className={'pw-tab' + (isActive ? ' active' : '')}
                      onClick={() => { setTab(t.id); setMoreOpen(false) }} aria-label={t.label} title={t.label}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={t.icon} /></svg>
                      <span className="pw-tab-label">{t.label}</span>
                    </button>
                  )
                })
              )}
              {/* More — portal dropdown, compact shows all non-active tabs */}
              <button ref={moreRef} type="button"
                className={'pw-tab pw-tab--more' + (
                  (panelCompact ? [...WS_MAIN_TABS, ...WS_MORE_TABS] : WS_MORE_TABS).some(t => t.id === tab) ? ' active' : ''
                )}
                onClick={() => {
                  const rect = moreRef.current?.getBoundingClientRect()
                  if (rect) setMorePos({ top: rect.bottom + 6, left: rect.left })
                  setMoreOpen(o => !o)
                }}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></svg>
                <svg className="pw-more-chev" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><path d="m6 9 6 6 6-6" /></svg>
              </button>
            </div>

            {/* Middle section */}
            <div className="pw-tab-mid">
              {tab === 'preview' ? (
                <>
                  <div className="pw-device-grp">
                    {WS_DEVICES.map(d => (
                      <button key={d.id} type="button" className={'pw-device-btn' + (device === d.id ? ' active' : '')}
                        title={d.label} aria-label={d.label} onClick={() => setDevice(d.id as typeof device)}>
                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={d.icon} /></svg>
                      </button>
                    ))}
                  </div>
                  <div className="pw-urlbar">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
                    <span>/preview</span>
                    <button type="button" aria-label="Reload" title="Reload">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12a9 9 0 1 1-2.6-6.4L21 8M21 3v5h-5" /></svg>
                    </button>
                  </div>
                </>
              ) : (
                <span className="pw-panel-title">{PANEL_TITLES[tab] ?? ''}</span>
              )}
            </div>

            {/* Right actions */}
            <button className="pw-panelbtn"
              onClick={() => setLayoutMode(layoutMode === 'preview-only' ? 'split' : 'preview-only')}
              title={layoutMode === 'preview-only' ? 'Restore split' : 'Expand panel'}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                {layoutMode === 'preview-only'
                  ? <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
                  : <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M16 21h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />}
              </svg>
            </button>
            {runActive && <button className="pw-hbtn" onClick={() => void cancel()} disabled={cancelling}>{cancelling ? 'Stopping…' : 'Stop'}</button>}
            {canRollback && <button className="pw-hbtn" onClick={() => void rollback()} disabled={rollingBack}>{rollingBack ? 'Restoring…' : 'Restore'}</button>}
            <button className={'pw-share-btn' + (panelCompact ? ' icon-only' : '')} onClick={() => setShareOpen(true)} title="Share">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M16 21v-2a4 4 0 0 0-8 0v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6" /></svg>
              {!panelCompact && 'Share'}
            </button>
            <button className={'pw-publish' + (panelCompact ? ' icon-only' : '')} onClick={() => setPublishOpen(true)} title={project?.deploymentStatus === 'LIVE' ? 'Published' : 'Publish'}>
              <span className="pw-livedot" />
              {!panelCompact && (project?.deploymentStatus === 'LIVE' ? 'Published' : 'Publish')}
            </button>
          </div>

          {/* More dropdown — portalled to avoid overflow:hidden clipping */}
          {moreOpen && morePos && createPortal(
            <>
              <div style={{ position: 'fixed', inset: 0, zIndex: 999 }} onClick={() => setMoreOpen(false)} />
              <div className="pw-more-pop" style={{ position: 'fixed', top: morePos.top, left: morePos.left, zIndex: 1000 }}>
                {(panelCompact ? [...WS_MAIN_TABS, ...WS_MORE_TABS] : WS_MORE_TABS)
                  .filter(t => !(panelCompact && (t.id === tab || (tab === 'thinking' && t.id === 'details'))))
                  .map(t => (
                    <button key={t.id} type="button" className={'pw-more-item' + (tab === t.id ? ' active' : '')}
                      onClick={() => { setTab(t.id); setMoreOpen(false) }}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d={t.icon} /></svg>
                      {t.label}
                    </button>
                  ))}
              </div>
            </>,
            document.body
          )}
          <div key={tab} className="pw-panel-content">
            {tab === 'thinking' && thinkingPanel ? <ThinkingPanel reasoning={thinkingPanel.reasoning} seconds={thinkingPanel.seconds} onBack={() => { setTab('details'); setThinkingPanel(null) }} />
              : tab === 'files' ? <FilesPanel projectId={id} activeRunId={runActive ? selectedRun?.id ?? null : null} />
              : tab === 'code' ? <CodePanel projectId={id} activeRunId={runActive ? selectedRun?.id ?? null : null} />
              : tab === 'history' ? <HistoryPanel runs={runs} onRestore={rollbackRun} rollingBackId={rollingBackId} />
              : tab === 'details' ? <DetailsPanel run={selectedRun} steps={steps} stage={project?.lifecycleStage} requirements={requirements} />
                  : tab === 'analytics' ? <AnalyticsPanel projectId={id} active={tab === 'analytics'} />
                    : tab === 'security' ? <SecurityPanel projectId={id} active={tab === 'security'} />
                      : tab === 'database' ? <DatabasePanel projectId={id} />
                        : <PreviewPanel project={project} />}
          </div>
        </section>
      </div>
    </AppShell>
  )
}

function isActive(run: AgentRun) {
  return run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'AWAITING_INPUT'
}

function needsRequirementInterview(project: Project | null): boolean {
  if (!project || project.type !== 'BUILD' || project.lifecycleStage !== 'REQUIREMENTS') return false
  const prompt = (project.initialPrompt ?? '').trim().toLowerCase()
  if (!prompt || /^(hi|hello|hey|yo|good morning|good evening|ok|okay)[.! ]*$/.test(prompt)) return false
  return prompt.length >= 9 && /\b(build|create|make|develop|design|implement|generate|clone|app|application|website|dashboard|todo|tool|platform|system|project|page|portal|crm|portfolio|store|shop|blog|mern)\b/.test(prompt)
}

function isInternalWorkflowMessage(message: Message): boolean {
  return message.role === 'USER' && message.content.startsWith('Build the requested project now. Use the approved requirements below as the source of truth.')
}

/* ── Chat ─────────────────────────────────────────────────────────────────── */
function ChatMessage({ message, run, steps, onOpenDetails, onOpenPreview, onOpenThinking, onFeedback, onEdit }: {
  message: Message; run?: AgentRun; steps: AgentStep[]
  onOpenDetails: () => void; onOpenPreview: () => void
  onOpenThinking: (reasoning: string, seconds: number) => void
  onFeedback: (feedback: MessageFeedback | null) => void
  onEdit: (content: string) => void
}) {
  if (message.role === 'USER') return <UserMessage message={message} onEdit={onEdit} />
  const pending = message.status === 'PENDING' || (message.status === 'STREAMING' && !message.content)
  const active = !!run && isActive(run)
  const settled = !!run && !active
  const changedFiles = settled ? changedFileCount(steps) : 0
  // "Building…" only when the model actually started writing files — a plain chat/explain reply
  // never earns the build checklist, just the thinking ticker below.
  const building = active && hasBuildActivity(steps)
  const reasoning = combinedReasoning(steps)
  const seconds = run && reasoning ? secondsBetween(run.createdAt, new Date(run.respondingAt ?? run.updatedAt).getTime()) : 0

  return (
    <article className="pw-agent" data-message-id={message.id} data-role="ASSISTANT">
      {pending
        ? (building ? <BuildProgress run={run!} steps={steps} /> : <ThinkingLine since={run?.createdAt ?? message.createdAt} />)
        : (
          <>
            {settled && run && (
              <div className="pw-agent-meta">
                {reasoning && (
                  <button type="button" className="pw-thinking" onClick={() => onOpenThinking(reasoning, seconds)}>
                    <Ico d={BRAIN_ICON} size={13} />
                    Thought for {seconds}s
                  </button>
                )}
                {changedFiles > 0 && (
                  <button type="button" className="pw-filesbtn" onClick={onOpenDetails}>
                    {changedFiles} file{changedFiles === 1 ? '' : 's'} changed
                  </button>
                )}
              </div>
            )}
            <Markdown content={message.content} />
            <MessageActions message={message} run={run} hasPreview={changedFiles > 0}
              onOpenPreview={onOpenPreview} onFeedback={onFeedback} />
          </>
        )}
    </article>
  )
}

const EDIT_ICON = 'M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z'

/** A user turn. Copy / edit / timestamp only reveal on hover (or focus, for keyboard use) so the
 * bubble itself stays as plain as the reference design — no permanently-visible action bar. */
function UserMessage({ message, onEdit }: { message: Message; onEdit: (content: string) => void }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(message.content)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard unavailable — nothing sensible to fall back to */ }
  }
  return (
    <div className="pw-user-wrap" data-message-id={message.id} data-role="USER">
      <article className="pw-user">{message.content}</article>
      <div className="pw-user-actions">
        <button type="button" className="pw-iconbtn" onClick={() => void copy()}
          title={copied ? 'Copied' : 'Copy'} aria-label="Copy message">
          <Ico d={copied ? CHECK_ICON : COPY_ICON} size={13} />
        </button>
        <button type="button" className="pw-iconbtn" onClick={() => onEdit(message.content)}
          title="Edit" aria-label="Edit message">
          <Ico d={EDIT_ICON} size={13} />
        </button>
        <span className="pw-user-time">{formatMessageTime(message.createdAt)}</span>
      </div>
    </div>
  )
}

function formatMessageTime(iso: string): string {
  const date = new Date(iso)
  const day = date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  const time = date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  return `${day} at ${time}`
}

const BUILD_TOOL_NAMES = new Set(['createFile', 'patchFile', 'deleteFile', 'renameFile'])

/** True once the run has actually started writing files, not merely exploring/reading them. */
function hasBuildActivity(steps: AgentStep[]): boolean {
  return steps.some((s) => s.type === 'TOOL_CALL' && !!s.content && BUILD_TOOL_NAMES.has(s.content))
}

/** Renders assistant text as formatted Markdown (headings, bold, lists, tables, code) instead of
 * raw "**"/"###"/"|" syntax — the model's replies are written expecting markdown rendering. */
function Markdown({ content }: { content: string }) {
  return <div className="pw-md"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown></div>
}

const COPY_ICON = 'M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2Z M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1'
const CHECK_ICON = 'M20 6 9 17l-5-5'
const THUMBS_UP_ICON = 'M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3'
const THUMBS_DOWN_ICON = 'M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h3a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-3'
const REPLY_ICON = 'M9 14 4 9l5-5 M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5v0a5.5 5.5 0 0 1-5.5 5.5H11'

export type Quote = { role: MessageRole; text: string }

/** Selecting text inside any message reveals a floating "Reply" pill above the selection; clicking it
 * quotes that exact snippet into the composer so the next prompt can target it precisely. Rendered as
 * a portal so it is never clipped by the chat list's overflow:auto. */
function SelectionReplyPopup({ containerRef, onQuote }: {
  containerRef: React.RefObject<HTMLElement | null>; onQuote: (quote: Quote) => void
}) {
  const [popup, setPopup] = useState<{ top: number; left: number; text: string; role: MessageRole } | null>(null)
  const popRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const handleUp = (e: MouseEvent) => {
      // A click on the pill itself fires its own mouseup here too (it's outside `container`, since
      // it's portaled to <body>) — ignore that so it doesn't re-open a popup for a stale selection.
      if (popRef.current?.contains(e.target as Node)) return
      window.setTimeout(() => {
        const selection = window.getSelection()
        const text = selection?.toString().trim() ?? ''
        if (!selection || selection.isCollapsed || !text) { setPopup(null); return }
        const anchor = selection.anchorNode
        if (!anchor || !container.contains(anchor)) { setPopup(null); return }
        const host = (anchor instanceof Element ? anchor : anchor.parentElement)?.closest<HTMLElement>('[data-message-id]')
        const role = host?.dataset.role as MessageRole | undefined
        if (!role) { setPopup(null); return }
        const rect = selection.getRangeAt(0).getBoundingClientRect()
        setPopup({ top: rect.top, left: rect.left + rect.width / 2, text, role })
      }, 0)
    }
    const handleDown = (e: MouseEvent) => {
      if (e.target instanceof Node && container.contains(e.target)) return
      // The pill lives outside `container` (portaled to <body>) — clicking it must not close it
      // before its own onClick fires, or the click reads as a "click outside" and the reply is lost.
      if (popRef.current?.contains(e.target as Node)) return
      setPopup(null)
    }
    container.addEventListener('mouseup', handleUp)
    document.addEventListener('mousedown', handleDown)
    return () => {
      container.removeEventListener('mouseup', handleUp)
      document.removeEventListener('mousedown', handleDown)
    }
  }, [containerRef])

  useEffect(() => {
    if (!popup) return
    const close = () => setPopup(null)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [popup])

  if (!popup) return null
  return createPortal(
    <button ref={popRef} type="button" className="pw-reply-pop" style={{ top: popup.top, left: popup.left }}
      onClick={() => {
        onQuote({ role: popup.role, text: popup.text })
        window.getSelection()?.removeAllRanges()
        setPopup(null)
      }}>
      <Ico d={REPLY_ICON} size={13} /> Reply
    </button>,
    document.body,
  )
}

function MessageActions({ message, run, hasPreview, onOpenPreview, onFeedback }: {
  message: Message; run?: AgentRun; hasPreview: boolean
  onOpenPreview: () => void; onFeedback: (feedback: MessageFeedback | null) => void
}) {
  const [copied, setCopied] = useState(false)
  const [menuPos, setMenuPos] = useState<{ top: number; left: number; openUpward: boolean } | null>(null)
  const menuBtnRef = useRef<HTMLButtonElement>(null)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(message.content)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard unavailable — nothing sensible to fall back to */ }
  }
  const toggleFeedback = (value: MessageFeedback) => onFeedback(message.feedback === value ? null : value)

  const menuOpen = !!menuPos
  const closeMenu = useCallback(() => setMenuPos(null), [])
  const openMenu = () => {
    const rect = menuBtnRef.current?.getBoundingClientRect()
    if (!rect) return
    const estimatedHeight = 140
    const openUpward = rect.bottom + estimatedHeight > window.innerHeight
    setMenuPos({ left: rect.left, top: openUpward ? rect.top : rect.bottom, openUpward })
  }

  // The message list scrolls independently of the page, and this menu is a portal (so it isn't
  // clipped by that scroll container) — without this it would drift away from its button on scroll.
  useEffect(() => {
    if (!menuOpen) return
    window.addEventListener('scroll', closeMenu, true)
    window.addEventListener('resize', closeMenu)
    return () => {
      window.removeEventListener('scroll', closeMenu, true)
      window.removeEventListener('resize', closeMenu)
    }
  }, [menuOpen, closeMenu])

  return (
    <div className="pw-msgactions">
      <button type="button" className={'pw-iconbtn' + (copied ? ' copied' : '')} onClick={() => void copy()}
        title={copied ? 'Copied' : 'Copy'} aria-label="Copy response">
        <Ico d={copied ? CHECK_ICON : COPY_ICON} size={14} />
      </button>
      <button type="button" className={'pw-iconbtn' + (message.feedback === 'UP' ? ' active' : '')}
        onClick={() => toggleFeedback('UP')} title="Good response" aria-label="Good response">
        <Ico d={THUMBS_UP_ICON} size={14} />
      </button>
      <button type="button" className={'pw-iconbtn' + (message.feedback === 'DOWN' ? ' active' : '')}
        onClick={() => toggleFeedback('DOWN')} title="Bad response" aria-label="Bad response">
        <Ico d={THUMBS_DOWN_ICON} size={14} />
      </button>
      {run && (
        <div className="pw-msgmenu-wrap">
          <button ref={menuBtnRef} type="button" className="pw-iconbtn"
            onClick={() => (menuOpen ? closeMenu() : openMenu())} aria-label="More actions">⋯</button>
          {menuOpen && menuPos && createPortal(
            <>
              <div className="pw-scrim-clear" onClick={closeMenu} />
              <div className="pw-msgmenu" style={{
                left: menuPos.left,
                top: menuPos.openUpward ? undefined : menuPos.top + 6,
                bottom: menuPos.openUpward ? window.innerHeight - menuPos.top + 6 : undefined,
              }}>
                <button type="button" className="pw-msgmenu-preview" disabled={!hasPreview}
                  onClick={() => { closeMenu(); onOpenPreview() }} title={hasPreview ? undefined : 'Nothing was built in this reply'}>
                  Preview
                </button>
                <div className="pw-msgmenu-sep" />
                <div className="pw-msgmenu-row"><span>Worked for</span><b>{formatDuration(run)}</b></div>
                <div className="pw-msgmenu-row"><span>Credits used</span><b>{creditsUsed(run)}</b></div>
              </div>
            </>,
            document.body,
          )}
        </div>
      )}
    </div>
  )
}

function formatDuration(run: AgentRun): string {
  const seconds = secondsBetween(run.createdAt, new Date(run.updatedAt).getTime())
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

/** Rough proxy until real usage-based billing exists: 1 credit per 1000 prompt+completion tokens. */
function creditsUsed(run: AgentRun): string {
  return ((run.promptTokens + run.completionTokens) / 1000).toFixed(1)
}


function changedFileCount(steps: AgentStep[]): number {
  const step = steps.find((s) => s.type === 'STATUS' && s.content === 'Change set committed')
  if (!step?.data) return 0
  try {
    const data = JSON.parse(step.data) as { changedFiles?: unknown }
    return Array.isArray(data.changedFiles) ? data.changedFiles.length : 0
  } catch {
    return 0
  }
}

function combinedReasoning(steps: AgentStep[]): string | null {
  const parts = steps.filter((s) => s.type === 'THINKING').map(reasoningFromStep).filter((s): s is string => !!s)
  return parts.length ? parts.join('\n\n') : null
}

/** The file-write checklist, embedded in the reply bubble in place of the plain thinking ticker —
 * shown only once the run has actually started writing files (see hasBuildActivity). */
function BuildProgress({ run, steps }: { run: AgentRun; steps: AgentStep[] }) {
  return (
    <div className="pw-buildcard">
      <b><span className="pw-orb" />Building…</b>
      <div className="pw-steps">
        {steps.map((step, i) => (
          <BuildStep key={step.seq} step={step} previousCreatedAt={steps[i - 1]?.createdAt ?? run.createdAt} />
        ))}
        <ThinkingNow since={steps[steps.length - 1]?.createdAt ?? run.createdAt} />
      </div>
    </div>
  )
}

/** Live-ticking "Thinking for Ns…" line shown while the model hasn't produced its next step yet. */
function ThinkingNow({ since }: { since: string }) {
  useTicker(true)
  return <div className="pw-step thinking"><span className="pw-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** The default pending state for a plain chat/explain reply: just a ticking "Thinking for Ns…" line,
 * no build checklist — that only appears once hasBuildActivity is true (see BuildProgress). */
function ThinkingLine({ since }: { since: string }) {
  useTicker(true)
  return <div className="pw-thinking-line"><span className="pw-spin" /> Thinking for {secondsBetween(since, Date.now())}s…</div>
}

/** Re-renders once a second while active, so live elapsed-time labels stay current. */
function useTicker(active: boolean) {
  const [, setTick] = useState(0)
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setTick((t) => t + 1), 1000)
    return () => clearInterval(id)
  }, [active])
}

function secondsBetween(fromIso: string, toMs: number): number {
  return Math.max(0, Math.round((toMs - new Date(fromIso).getTime()) / 1000))
}

/* ── Version history (Figma 18) — every agent run, newest first, with per-run restore ─────────── */
const RUN_STATUS_META: Record<string, { label: string; color: string }> = {
  DONE: { label: 'Complete', color: '#0B6B45' },
  RUNNING: { label: 'Running', color: '#3D30C9' },
  QUEUED: { label: 'Queued', color: '#8A6D00' },
  AWAITING_INPUT: { label: 'Awaiting input', color: '#8A6D00' },
  FAILED: { label: 'Failed', color: '#B42318' },
  CANCELLED: { label: 'Cancelled', color: '#6E6B74' },
}

function runWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function HistoryPanel({ runs, onRestore, rollingBackId }: { runs: AgentRun[]; onRestore: (id: string) => void; rollingBackId: string | null }) {
  if (!runs.length) return <div className="pw-emptyfiles">No builds yet — ask Kiln to build something and each run will appear here.</div>
  return (
    <div className="pw-history">
      {runs.map((run) => {
        const meta = RUN_STATUS_META[run.status] ?? { label: run.status, color: '#6E6B74' }
        const tokens = run.promptTokens + run.completionTokens
        const canRestore = run.mode === 'BUILD' && run.rollbackAvailable
        return (
          <div className="pw-hrow" key={run.id}>
            <span className="pw-hmode">{run.mode}</span>
            <div className="pw-hmain">
              <b>{meta.label} · {run.mode.toLowerCase()} run</b>
              <small>{runWhen(run.createdAt)}{tokens > 0 && ` · ${tokens.toLocaleString()} tokens`}</small>
            </div>
            <span className="pw-hstatus" style={{ color: meta.color }}>{meta.label}</span>
            {canRestore && (
              <button className="pw-hrestore" onClick={() => onRestore(run.id)} disabled={rollingBackId === run.id}>
                {rollingBackId === run.id ? 'Restoring…' : 'Restore'}
              </button>
            )}
          </div>
        )
      })}
    </div>
  )
}

/* ── Build details (Figma 17) — the current run's metadata + full step timeline ────────────────── */
function RequirementSummary({ session }: { session: RequirementSession }) {
  const answered = session.questions.filter((question) => question.selectedOption || question.otherText).length
  return (
    <div className="pw-requirement-summary">
      <div className="pw-requirement-summary-head">
        <div><b>{session.status === 'APPROVED' ? 'Requirements approved' : 'Questions answered'}</b><small>{answered} of {session.questions.length} answered</small></div>
        <span className={'pw-summary-pulse' + (session.complete ? ' done' : '')} />
      </div>
      <div className="pw-requirement-summary-list">
        {session.questions.map((question) => {
          const answer = question.otherText || question.options.find((option) => option.id === question.selectedOption)?.label
          return <div className="pw-requirement-summary-row" key={question.id}><span>{question.prompt}</span><b>{answer ?? 'Waiting for answer'}</b></div>
        })}
      </div>
    </div>
  )
}

function ProcessRail({ stage }: { stage?: Project['lifecycleStage'] }) {
  const stages = [
    ['REQUIREMENTS', 'Requirements'], ['DESIGN', 'Design'], ['BUILDING', 'Build'],
    ['REVIEW', 'Review'], ['DEPLOYING', 'Deploy'], ['LIVE', 'Live'],
  ] as const
  const current = Math.max(0, stages.findIndex(([id]) => id === stage))
  return (
    <div className="pw-process-rail" aria-label="Project process">
      {stages.map(([id, label], index) => (
        <div key={id} className={'pw-process-rail-step' + (id === stage ? ' active' : '') + (index < current || stage === 'LIVE' ? ' complete' : '')}>
          <span>{index < current || stage === 'LIVE' ? 'x' : index + 1}</span>
          <b>{label}</b>
        </div>
      ))}
    </div>
  )
}

function DetailsPanel({ run, steps, stage, requirements }: { run: AgentRun | undefined; steps: AgentStep[]; stage?: Project['lifecycleStage']; requirements: RequirementSession | null }) {
  if (!run && requirements) return <div className="pw-details"><ProcessRail stage={stage} /><RequirementSummary session={requirements} /></div>
  if (!run) return <div className="pw-emptyfiles">No build to show yet — start a build and its details appear here.</div>
  const meta = RUN_STATUS_META[run.status] ?? { label: run.status, color: '#6E6B74' }
  return (
    <div className="pw-details">
      <div className="pw-details-head">
        <b>{run.mode.toLowerCase()} run</b>
        <span className="pw-details-status" style={{ color: meta.color }}>{meta.label}</span>
      </div>
      <ProcessRail stage={stage} />
      {requirements && <RequirementSummary session={requirements} />}
      <dl className="pw-details-meta">
        <div><dt>Started</dt><dd>{runWhen(run.createdAt)}</dd></div>
        <div><dt>Prompt tokens</dt><dd>{run.promptTokens.toLocaleString()}</dd></div>
        <div><dt>Completion tokens</dt><dd>{run.completionTokens.toLocaleString()}</dd></div>
        <div><dt>Restore point</dt><dd>{run.rollbackAvailable ? 'Available' : '—'}</dd></div>
      </dl>
      <div className="pw-details-steps">
        {steps.length
          ? steps.map((step, i) => <BuildStep key={step.seq} step={step} previousCreatedAt={steps[i - 1]?.createdAt ?? run.createdAt} />)
          : <p className="pw-loading">No steps recorded for this run.</p>}
      </div>
    </div>
  )
}

/* ── Thinking panel — full reasoning for a specific "Thought for Ns" click ─────────────────────── */
function ThinkingPanel({ reasoning, seconds, onBack }: { reasoning: string; seconds: number; onBack: () => void }) {
  const [expanded, setExpanded] = useState(true)
  return (
    <div className="pw-thinking-panel">
      <div className="pw-thinking-panel-hd">
        <button type="button" className="pw-tp-back" onClick={onBack}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5M12 5l-7 7 7 7" /></svg>
          Back to latest
        </button>
        <div className="pw-tp-meta">
          <span className="pw-tp-stat">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" /></svg>
            {seconds}s
          </span>
        </div>
        <span className="pw-tp-title">Details</span>
        <div className="pw-tp-tabs">
          <button type="button" className="pw-tp-tab pw-tp-tab--on">Timeline</button>
          <button type="button" className="pw-tp-tab">Changes</button>
        </div>
      </div>
      <div className="pw-thinking-panel-body">
        <div className="pw-tp-card">
          <button type="button" className="pw-tp-card-hd" onClick={() => setExpanded(e => !e)}>
            <Ico d={BRAIN_ICON} size={14} />
            <span>Thought for {seconds}s</span>
            <svg className={`pw-tp-chevron${expanded ? ' pw-tp-chevron--up' : ''}`} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          {expanded && (
            <div className="pw-tp-card-body">
              {reasoning
                ? <Markdown content={reasoning} />
                : <p className="pw-thinking-empty">No reasoning was streamed for this response.</p>}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/* ── Files panel — project file tree ─────────────────────────────────────────────────────────── */
function mergeStagedTree(tree: FileNode[], staged: StagedFile[]): FileNode[] {
  const clone = (node: FileNode): FileNode => ({ ...node, children: node.children?.map(clone) ?? null })
  let result = tree.map(clone)
  for (const change of staged) {
    const parts = change.path.replaceAll('\\', '/').split('/').filter(Boolean)
    if (!parts.length) continue
    const apply = (nodes: FileNode[], index: number): FileNode[] => {
      const name = parts[index]
      const at = nodes.findIndex(node => node.name === name)
      const last = index === parts.length - 1
      if (last) {
        if (change.deleted) return at < 0 ? nodes : nodes.filter((_, nodeIndex) => nodeIndex !== at)
        const previous = at >= 0 ? nodes[at] : null
        const next: FileNode = {
          name,
          path: change.path,
          dir: previous?.dir ?? false,
          size: change.content?.length ?? previous?.size ?? 0,
          children: previous?.children ?? null,
        }
        if (at < 0) return [...nodes, next]
        return nodes.map((node, nodeIndex) => nodeIndex === at ? next : node)
      }
      if (at < 0) {
        if (change.deleted) return nodes
        const path = parts.slice(0, index + 1).join('/')
        const created: FileNode = { name, path, dir: true, size: 0, children: apply([], index + 1) }
        return [...nodes, created]
      }
      const current = nodes[at]
      if (!current.dir) return nodes
      const next = { ...current, children: apply(current.children ?? [], index + 1) }
      return nodes.map((node, nodeIndex) => nodeIndex === at ? next : node)
    }
    result = apply(result, 0)
  }
  return result
}

function FilesPanel({ projectId, activeRunId }: { projectId: string; activeRunId: string | null }) {
  const { data: tree, isLoading } = useFileTree(projectId)
  const { data: staged } = useStagedFiles(projectId, activeRunId, !!activeRunId)
  const visibleTree = useMemo(() => mergeStagedTree(tree ?? [], staged ?? []), [tree, staged])

  function renderNode(node: FileNode, depth = 0): React.ReactNode {
    const isDir = node.dir
    return (
      <div key={node.path} style={{ paddingLeft: depth * 14 }}>
        <div className={'pw-file-row' + (isDir ? ' pw-file-dir' : '')}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            {isDir
              ? <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
              : <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6" />}
          </svg>
          <span>{node.name}</span>
        </div>
        {isDir && node.children?.map(child => renderNode(child, depth + 1))}
      </div>
    )
  }

  if (isLoading) return <div className="pw-insight"><LoadingState /></div>
  if (!visibleTree.length) return (
    <div className="pw-empty">
      <div>
        <span className="pw-empty-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" /></svg>
        </span>
        <h3>No files yet</h3>
        <p>Files will appear here once the agent starts building.</p>
      </div>
    </div>
  )
  return <div className="pw-file-tree">{visibleTree.map(n => renderNode(n))}</div>
}

/* ── Analytics (Figma 21) — real activity aggregates ──────────────────────────────────────────── */
function AnalyticsPanel({ projectId, active }: { projectId: string; active: boolean }) {
  const { data, isLoading, isError, refetch } = useAnalytics(projectId, active)
  if (isLoading) return <div className="pw-insight"><LoadingState /></div>
  if (isError || !data) return <div className="pw-insight"><ErrorState message="Couldn't load analytics." onRetry={() => void refetch()} /></div>
  const rate = data.totalRuns ? Math.round((data.completedRuns / data.totalRuns) * 100) : 0
  const stats: [string, string | number][] = [
    ['Builds', data.totalRuns], ['Completed', data.completedRuns], ['Failed', data.failedRuns],
    ['Success rate', `${rate}%`], ['Messages', data.totalMessages], ['Files', data.fileCount],
    ['Tokens used', data.totalTokens.toLocaleString()], ['Status', data.deploymentStatus],
  ]
  return (
    <div className="pw-insight">
      <h2>Analytics</h2>
      <p className="pw-insight-sub">Real activity for this project.</p>
      <div className="pw-stat-grid">
        {stats.map(([label, val]) => <div className="pw-stat" key={label}><span>{label}</span><b>{val}</b></div>)}
      </div>
      <p className="pw-insight-meta">Created {new Date(data.createdAt).toLocaleDateString()} · Last activity {new Date(data.lastActivityAt).toLocaleDateString()}</p>
    </div>
  )
}

/* ── Security scan (Figma 23) — real static scan of workspace files ────────────────────────────── */
function SecurityPanel({ projectId, active }: { projectId: string; active: boolean }) {
  const { data, isLoading, isError, refetch } = useSecurityScan(projectId, active)
  if (isLoading) return <div className="pw-insight"><LoadingState label="Scanning workspace…" /></div>
  if (isError || !data) return <div className="pw-insight"><ErrorState message="Couldn't run the security scan." onRetry={() => void refetch()} /></div>
  return (
    <div className="pw-insight">
      <h2>Security scan</h2>
      <p className="pw-insight-sub">Static check of {data.scannedFiles} workspace file{data.scannedFiles === 1 ? '' : 's'}.</p>
      <div className="pw-sec-summary">
        <span className="pw-sev high">{data.high} high</span>
        <span className="pw-sev medium">{data.medium} medium</span>
        <span className="pw-sev low">{data.low} low</span>
      </div>
      {data.findings.length === 0
        ? <EmptyState title="No issues found" hint="No secrets or risky patterns detected in the scanned files." />
        : <div className="pw-sec-list">
            {data.findings.map((f, i) => (
              <div className="pw-sec-row" key={i}>
                <span className={`pw-sev ${f.severity.toLowerCase()}`}>{f.severity}</span>
                <div><b>{f.title}</b><small>{f.file} · {f.detail}</small></div>
              </div>
            ))}
          </div>}
    </div>
  )
}

/* ── Cloud database (Figma 22) — honest empty state (no managed DB backend yet) ────────────────── */
function DatabasePanel({ projectId }: { projectId: string }) {
  const { data, isLoading, isError, refetch } = useDatabase(projectId)
  if (isLoading) return <div className="pw-insight"><LoadingState /></div>
  if (isError || !data) return <div className="pw-insight"><ErrorState message="Could not load database status." onRetry={() => void refetch()} /></div>
  return (
    <div className="pw-insight">
      <h2>Project database</h2>
      <p className="pw-insight-sub">The MongoDB namespace reserved for this generated app.</p>
      <div className="pw-db-status"><span className={'pw-sev ' + (data.reachable ? 'low' : 'medium')}>{data.reachable ? 'Connected' : 'Unavailable'}</span><code>{data.database}</code></div>
      {data.reachable ? <div className="pw-db-collections">{data.collections.length ? data.collections.map((name) => <span key={name}>{name}</span>) : <span>No collections yet</span>}</div> : <EmptyState title="MongoDB is unavailable" hint="Start the local MongoDB service to inspect this project's data." />}
    </div>
  )
}

function BuildStep({ step, previousCreatedAt }: { step: AgentStep; previousCreatedAt?: string }) {
  if (step.type === 'THINKING') return <ThinkingStep step={step} previousCreatedAt={previousCreatedAt} />
  const title = step.type === 'TOOL_CALL' ? friendlyToolTitle(step.content)
    : step.type === 'TOOL_RESULT' ? `${friendlyToolTitle(step.content)} complete`
      : step.type === 'STATUS' ? (step.content ?? 'Status')
        : step.type.replace('_', ' ')
  const file = fileFromStep(step)
  return (
    <div className="pw-step">
      <span className="pw-dot" />
      <span className="pw-steplabel">{title}</span>
      {file && <code>{file}</code>}
    </div>
  )
}

function friendlyToolTitle(tool: string | null): string {
  switch (tool) {
    case 'setPlan': return 'Created implementation plan'
    case 'updatePlanStep': return 'Updated implementation plan'
    case 'listFiles': return 'Read project files'
    case 'readFile': return 'Read file'
    case 'getFileSummary': return 'Summarized file'
    case 'searchCode': return 'Searched code'
    case 'getProjectMetadata': return 'Read project metadata'
    case 'createFile': return 'Created file'
    case 'writeFile': return 'Wrote file'
    case 'patchFile': return 'Edited file'
    case 'deleteFile': return 'Deleted file'
    case 'renameFile': return 'Renamed file'
    case 'getDiagnostics': return 'Checked diagnostics'
    case 'runTypeCheck': return 'Checked types'
    case 'runBuild': return 'Checked production build'
    case 'runTests': return 'Ran tests'
    case 'validateChanges': return 'Validated changes'
    default: return tool ? tool.replace(/([a-z])([A-Z])/g, '$1 $2') : 'Agent step'
  }
}

/** A "Thought for Ns" step expands in place to show the model's actual reasoning right inside
 * the Details panel without redirecting away — otherwise it expands to a short note instead of looking unresponsive. */
function ThinkingStep({ step, previousCreatedAt }: { step: AgentStep; previousCreatedAt?: string }) {
  const [expanded, setExpanded] = useState(false)
  const [copied, setCopied] = useState(false)
  const reasoning = reasoningFromStep(step)
  const seconds = secondsBetween(previousCreatedAt ?? step.createdAt, new Date(step.createdAt).getTime())

  const copyReasoning = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (!reasoning) return
    void navigator.clipboard.writeText(reasoning)
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  return (
    <div className="pw-thinking-step">
      <button
        type="button"
        className={`pw-thinking${expanded ? ' active' : ''}`}
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        title={expanded ? 'Collapse thinking' : 'Expand thinking'}
      >
        <Ico d={BRAIN_ICON} size={13} />
        <span>Thought for {seconds}s</span>
        <svg
          className={`pw-thinking-chev${expanded ? ' open' : ''}`}
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {expanded && (
        <div className="pw-thinking-inline">
          <div className="pw-thinking-inline-hd">
            <span className="pw-thinking-inline-title">Reasoning · {seconds}s</span>
            {reasoning && (
              <button
                type="button"
                className="pw-thinking-inline-copy"
                onClick={copyReasoning}
                title={copied ? 'Copied to clipboard' : 'Copy reasoning'}
              >
                <Ico d={copied ? CHECK_ICON : COPY_ICON} size={12} />
                <span>{copied ? 'Copied' : 'Copy'}</span>
              </button>
            )}
          </div>
          <div className="pw-thinking-inline-body">
            {reasoning ? (
              <Markdown content={reasoning} />
            ) : (
              <p className="pw-thinking-empty">No reasoning was streamed for this response.</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function fileFromStep(step: AgentStep): string | null {
  if (!step.data) return null
  try {
    const data = JSON.parse(step.data) as { path?: string }
    return typeof data.path === 'string' ? data.path : null
  } catch {
    return null
  }
}

function reasoningFromStep(step: AgentStep): string | null {
  if (step.data) {
    try {
      const data = JSON.parse(step.data) as { reasoning?: string }
      if (typeof data.reasoning === 'string' && data.reasoning.trim()) return data.reasoning
    } catch {
      // not JSON
    }
  }
  if (typeof step.content === 'string' && step.content.trim() && step.content !== 'Model reasoning') {
    return step.content
  }
  return null
}

const FILE_ICON = 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z'
const CLOSE_ICON = 'M18 6L6 18M6 6l12 12'

const COMPOSER_MODES: { id: ProjectType; label: string; icon: string }[] = [
  { id: 'BUILD', label: 'Build', icon: 'M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z' },
  { id: 'PLAN',  label: 'Plan',  icon: 'M9 5h11M9 12h11M9 19h11M4 5h.01M4 12h.01M4 19h.01' },
  { id: 'CHAT',  label: 'Chat',  icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z' },
]

function Composer({ defaultMode, onSend, quote, onClearQuote, editRequest }: {
  defaultMode: ProjectType; onSend: (prompt: string, mode: ProjectType, files: File[]) => void
  quote: Quote | null; onClearQuote: () => void
  editRequest: { text: string; nonce: number } | null
}) {
  const [draft, setDraft] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [attachOpen, setAttachOpen] = useState(false)
  const [mode, setMode] = useState<ProjectType>(defaultMode)
  const [modeOpen, setModeOpen] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const currentMode = COMPOSER_MODES.find((m) => m.id === mode) ?? COMPOSER_MODES[0]

  useEffect(() => { if (quote) textareaRef.current?.focus() }, [quote])

  // "Edit" on a past user message loads its text back into the composer to tweak and resend as a new
  // turn — it does not mutate or delete the original message. Keyed on `editRequest`'s object identity
  // (a nonce), not its text, so re-editing the same message twice in a row still re-triggers this.
  useEffect(() => {
    if (!editRequest) return
    setDraft(editRequest.text)
    textareaRef.current?.focus()
  }, [editRequest])

  const addFiles = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files) setFiles((previous) => [...previous, ...Array.from(e.target.files!)])
    if (fileInputRef.current) fileInputRef.current.value = ''
  }
  const removeFile = (index: number) => setFiles((previous) => previous.filter((_, i) => i !== index))

  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    const text = draft.trim()
    if (!text) return
    const prompt = quote ? `> ${quote.text.replace(/\n/g, '\n> ')}\n\n${text}` : text
    onSend(prompt, mode, files)
    setDraft('')
    setFiles([])
    onClearQuote()
  }
  return (
    <form className="pw-composer" onSubmit={submit}>
      {quote && (
        <div className="pw-quote">
          <Ico d={REPLY_ICON} size={13} />
          <span>{quote.text}</span>
          <button type="button" onClick={onClearQuote} aria-label="Remove quoted reference">
            <Ico d={CLOSE_ICON} size={11} />
          </button>
        </div>
      )}
      {files.length > 0 && (
        <div className="pw-attachments">
          {files.map((file, i) => (
            <div className="pw-attachment" key={i}>
              <Ico d={FILE_ICON} size={13} />
              <span>{file.name}</span>
              <button type="button" onClick={() => removeFile(i)} aria-label={`Remove ${file.name}`}>
                <Ico d={CLOSE_ICON} size={11} />
              </button>
            </div>
          ))}
        </div>
      )}
      <textarea
        ref={textareaRef}
        rows={2}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) submit(e) }}
        placeholder={quote ? 'Ask about the selected part…' : 'Ask Kiln to make a change…'}
      />
      <footer>
        <input type="file" multiple ref={fileInputRef} style={{ display: 'none' }} onChange={addFiles} />
        <div className="pw-attach-wrap">
          <button type="button" className="pw-attach" aria-label="Add attachment" onClick={() => setAttachOpen((v) => !v)}>
            <Ico d="M12 5v14M5 12h14" size={15} />
          </button>
          {attachOpen && (
            <>
              <div className="pw-attach-scrim" onClick={() => setAttachOpen(false)} />
              <div className="pw-attach-pop">
                <button type="button" onClick={() => { setAttachOpen(false); fileInputRef.current?.click() }}>
                  <Ico d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" size={15} />
                  <span><b>Upload a file</b><small>Upload from your device</small></span>
                </button>
                <button type="button" onClick={() => setAttachOpen(false)}>
                  <Ico d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" size={15} />
                  <span><b>Import an existing project</b><small>Bring in code from GitHub or a folder</small></span>
                </button>
                <button type="button" onClick={() => setAttachOpen(false)}>
                  <Ico d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" size={15} />
                  <span><b>Use a skill</b><small>Invoke a saved skill or tool</small></span>
                </button>
              </div>
            </>
          )}
        </div>
        {/* Mode selector */}
        <div style={{ position: 'relative' }}>
          <button type="button" className="kap-cmode" aria-expanded={modeOpen} onClick={() => setModeOpen((v) => !v)}>
            <Ico d={currentMode.icon} size={14} /> {currentMode.label}
            <Ico d="m6 9 6 6 6-6" size={12} />
          </button>
          {modeOpen && (
            <>
              <div className="kap-scrim" onClick={() => setModeOpen(false)} />
              <div className="kap-pop kap-mode-pop">
                {COMPOSER_MODES.map((m) => (
                  <button key={m.id} type="button" className={m.id === mode ? 'on' : ''} onClick={() => { setMode(m.id); setModeOpen(false) }}>
                    <Ico d={m.icon} size={14} />
                    <span style={{ flexGrow: 1 }}><b>{m.label}</b></span>
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
        <div style={{ flexGrow: 1 }} />
        <button className="pw-send" aria-label="Send" disabled={!draft.trim()}>↑</button>
      </footer>
    </form>
  )
}

/* ── Preview ──────────────────────────────────────────────────────────────── */
function PreviewPanel({ project }: { project: Project | null }) {
  // The backend returns the full URL: previews live on a separate sites origin (never the API
  // origin), so the generated app cannot reach the API with the viewer's cookies.
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [previewError, setPreviewError] = useState(false)
  const live = project?.deploymentStatus === 'LIVE'

  useEffect(() => {
    setPreviewUrl(null)
    setPreviewError(false)
    if (!project || live || project.lifecycleStage !== 'REVIEW') return
    let cancelled = false
    void projectApi.previewToken(project.id)
      .then((response) => { if (!cancelled) setPreviewUrl(response.url) })
      .catch(() => { if (!cancelled) setPreviewError(true) })
    return () => { cancelled = true }
  }, [project?.id, project?.lifecycleStage, live])

  const url = live ? project?.publishedUrl : previewUrl
  if (url) return <iframe title="App preview" src={url} className="pw-frame" sandbox="allow-scripts allow-same-origin allow-forms" />
  return (
    <div className="pw-empty pw-dots">
      <div>
        <span className="pw-empty-icon">◐</span>
        <h3>{previewError ? 'Preview unavailable' : project?.lifecycleStage === 'REVIEW' ? 'Preparing preview' : 'No preview yet'}</h3>
        <p>Your app’s live preview will appear here once it can run. For now, open the <b>Code</b> tab to see the files Kiln has written.</p>
      </div>
    </div>
  )
}

/* ── Code / files ─────────────────────────────────────────────────────────── */
/** Cached via React Query (see useFileTree/useFileContent in api/queries.ts) instead of a raw fetch
 * effect — switching between files you've already opened shows the cached content instantly, with no
 * network round trip, and a build only invalidates this project's file queries (see ProjectPage's
 * onRealtime), not a manually-threaded refreshKey. */
function sortNodes(nodes: FileNode[]): FileNode[] {
  return [...nodes].sort((a, b) => {
    if (a.dir !== b.dir) return a.dir ? -1 : 1
    return a.name.localeCompare(b.name)
  })
}

function FileIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  if (lower.endsWith('.json')) {
    return <span className="pw-badge-icon pw-badge-json">{'{ }'}</span>
  }
  if (lower.endsWith('.tsx') || lower.endsWith('.jsx')) {
    return (
      <svg className="pw-tree-svg react" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <ellipse cx="12" cy="12" rx="10" ry="4" />
        <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(60 12 12)" />
        <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(120 12 12)" />
        <circle cx="12" cy="12" r="1.5" fill="currentColor" />
      </svg>
    )
  }
  if (lower.endsWith('.ts')) {
    return <span className="pw-badge-icon pw-badge-ts">TS</span>
  }
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) {
    return <span className="pw-badge-icon pw-badge-js">JS</span>
  }
  if (lower.endsWith('.css') || lower.endsWith('.scss')) {
    return <span className="pw-badge-icon pw-badge-css">CSS</span>
  }
  if (lower.includes('git')) {
    return (
      <svg className="pw-tree-svg git" width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 2a2 2 0 0 0-1.4.6l-8 8a2 2 0 0 0 0 2.8l8 8a2 2 0 0 0 2.8 0l8-8a2 2 0 0 0 0-2.8l-8-8A2 2 0 0 0 12 2zm0 4a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm-3.5 5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z" />
      </svg>
    )
  }
  if (lower.includes('prettier') || lower.includes('eslint')) {
    return (
      <svg className="pw-tree-svg config" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <polygon points="12 2 21 7 21 17 12 22 3 17 3 7" />
        <circle cx="12" cy="12" r="2" />
      </svg>
    )
  }
  if (lower.includes('lock') || lower.includes('bun')) {
    return (
      <svg className="pw-tree-svg lock" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
      </svg>
    )
  }
  return (
    <svg className="pw-tree-svg doc" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
    </svg>
  )
}

function CodePanel({ projectId, activeRunId }: { projectId: string; activeRunId: string | null }) {
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [copied, setCopied] = useState(false)
  const [openDirs, setOpenDirs] = useState<Set<string>>(() => new Set())
  const initializedDirs = useRef(false)

  const [sidebarWidth, setSidebarWidth] = useState(() => {
    try {
      const saved = localStorage.getItem('pw-code-sidebar-width')
      if (saved) {
        const n = parseInt(saved, 10)
        if (!isNaN(n) && n >= 140 && n <= 700) return n
      }
    } catch { /* ignore */ }
    return 240
  })
  const [resizing, setResizing] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  const { data: tree, isError: treeErrored } = useFileTree(projectId)
  const { data: staged } = useStagedFiles(projectId, activeRunId, !!activeRunId)
  const visibleTree = useMemo(() => mergeStagedTree(tree ?? [], staged ?? []), [tree, staged])
  const { data: file, isFetching: loadingFile } = useFileContent(projectId, selected)
  const stagedFile = staged?.find((item) => item.path === selected && !item.deleted)

  // Expand top-level directories by default on first load
  useEffect(() => {
    if (visibleTree.length && !initializedDirs.current) {
      initializedDirs.current = true
      const toOpen = new Set<string>()
      const collect = (nodes: FileNode[], depth = 0) => {
        for (const n of nodes) {
          if (n.dir) {
            if (depth <= 1) toOpen.add(n.path)
            if (n.children) collect(n.children, depth + 1)
          }
        }
      }
      collect(visibleTree)
      setOpenDirs(toOpen)
    }
  }, [visibleTree])

  const toggleDir = (dirPath: string) => {
    setOpenDirs((prev) => {
      const next = new Set(prev)
      if (next.has(dirPath)) next.delete(dirPath)
      else next.add(dirPath)
      return next
    })
  }

  const allFiles = useMemo(() => {
    if (!visibleTree) return []
    const flat: FileNode[] = []
    const collect = (nodes: FileNode[]) => {
      for (const n of nodes) {
        if (!n.dir) flat.push(n)
        if (n.children) collect(n.children)
      }
    }
    collect(visibleTree)
    return flat
  }, [visibleTree])

  const matchingFiles = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    return allFiles.filter((f) => f.path.toLowerCase().includes(q) || f.name.toLowerCase().includes(q))
  }, [allFiles, query])

  const copy = async () => {
    if (!file) return
    try { await navigator.clipboard.writeText(file.content); setCopied(true); setTimeout(() => setCopied(false), 1500) } catch { /* ignore */ }
  }
  const download = () => {
    if (!file) return
    const blob = new Blob([file.content], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = selected?.split('/').pop() || 'file.txt'
    a.click()
    URL.revokeObjectURL(url)
  }

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    setResizing(true)
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!resizing) return
    const container = containerRef.current
    if (!container) return
    const rect = container.getBoundingClientRect()
    const raw = e.clientX - rect.left
    const minWidth = 160
    const maxWidth = Math.max(minWidth, rect.width - 200)
    setSidebarWidth(clamp(raw, minWidth, maxWidth))
  }

  const handlePointerUp = () => {
    if (resizing) {
      setResizing(false)
      try {
        localStorage.setItem('pw-code-sidebar-width', String(Math.round(sidebarWidth)))
      } catch { /* ignore */ }
    }
  }

  const renderNode = (node: FileNode, depth = 0): React.ReactNode => {
    if (node.dir) {
      const isOpen = openDirs.has(node.path)
      const sortedChildren = node.children ? sortNodes(node.children) : []
      return (
        <div key={node.path}>
          <div
            className={`pw-file-row pw-file-dir${isOpen ? ' open' : ''}`}
            style={{ paddingLeft: 8 + depth * 14 }}
            onClick={() => toggleDir(node.path)}
          >
            <span className="pw-tree-arrow">
              <svg
                className={`pw-chevron-icon${isOpen ? ' open' : ''}`}
                width="11"
                height="11"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </span>
            <span className="pw-file-name">{node.name}</span>
            <button
              type="button"
              className="pw-file-more"
              onClick={(e) => e.stopPropagation()}
              title="Options"
            >
              ⋯
            </button>
          </div>
          {isOpen && (
            <div>
              {sortedChildren.map((child) => renderNode(child, depth + 1))}
            </div>
          )}
        </div>
      )
    }

    const isSelected = selected === node.path
    return (
      <div
        key={node.path}
        className={`pw-file-row pw-file-item${isSelected ? ' selected' : ''}`}
        style={{ paddingLeft: 8 + depth * 14 + 14 }}
        onClick={() => setSelected(node.path)}
      >
        <FileIcon name={node.name} />
        <span className="pw-file-name">{node.name}</span>
      </div>
    )
  }

  if (!tree && !treeErrored) return <div className="pw-code"><div className="pw-loading">Loading files…</div></div>
  if (!visibleTree || visibleTree.length === 0) {
    return <div className="pw-code"><div className="pw-emptyfiles">No files yet — when Kiln builds in this project, the files it writes show up here.</div></div>
  }

  const sortedTree = sortNodes(visibleTree)

  return (
    <div
      className={`pw-code${resizing ? ' resizing' : ''}`}
      ref={containerRef}
      style={{ userSelect: resizing ? 'none' : undefined }}
    >
      <aside style={{ width: sidebarWidth, minWidth: sidebarWidth, maxWidth: sidebarWidth }}>
        <div className="pw-code-search-wrap">
          <div className="pw-code-search-box">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search code"
              aria-label="Search code"
            />
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <path d="m7 15 5 5 5-5M7 9l5-5 5 5" />
            </svg>
          </div>
        </div>

        {query.trim() ? (
          matchingFiles.length === 0 ? (
            <div className="pw-explorer-empty">No files match “{query}”.</div>
          ) : (
            matchingFiles.map((f) => (
              <div
                key={f.path}
                className={`pw-file-row pw-file-item${selected === f.path ? ' selected' : ''}`}
                style={{ paddingLeft: 12 }}
                onClick={() => setSelected(f.path)}
              >
                <FileIcon name={f.name} />
                <span className="pw-file-name">{f.path}</span>
              </div>
            ))
          )
        ) : (
          sortedTree.map((node) => renderNode(node, 0))
        )}
      </aside>

      <div
        className={`pw-code-resizer${resizing ? ' dragging' : ''}`}
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize files sidebar"
        title="Drag to resize file list"
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onDoubleClick={() => {
          setSidebarWidth(240)
          try { localStorage.setItem('pw-code-sidebar-width', '240') } catch {}
        }}
      />

      {!selected ? (
        <div className="pw-code-empty-pane" />
      ) : (
        <article>
          <div className="pw-code-tabbar">
            <div className="pw-code-tab">
              <span className="pw-code-tab-name">{selected}</span>
              <button
                type="button"
                className="pw-code-tab-close"
                onClick={() => setSelected(null)}
                title="Close file"
                aria-label="Close file"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>
            <div className="pw-code-tabbar-actions">
              <button
                type="button"
                className="pw-code-icon-btn"
                onClick={() => void copy()}
                title={copied ? 'Copied' : 'Copy code'}
              >
                <Ico d={copied ? CHECK_ICON : COPY_ICON} size={14} />
              </button>
              <button
                type="button"
                className="pw-code-action-btn"
                onClick={download}
                title="Download file"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
                </svg>
                <span>Download</span>
              </button>
            </div>
          </div>
          {loadingFile ? (
            <div className="pw-loading">Loading…</div>
          ) : file?.binary || (stagedFile && stagedFile.content === null) ? (
            <div className="pw-emptyfiles">Binary file — cannot preview.</div>
          ) : (
            <CodeLines content={stagedFile?.content ?? file?.content ?? ''} filename={selected} />
          )}
        </article>
      )}
    </div>
  )
}

function highlightLine(line: string, ext: string): React.ReactNode {
  if (!line) return ' '
  if (ext === 'json') {
    const parts: React.ReactNode[] = []
    const regex = /("(?:\\.|[^"\\])*"(?:\s*:)?)|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(\b(?:true|false|null)\b)|([{}[\],:])|(\s+)|([^"0-9a-zA-Z\s{}[\],:]+)/g
    let match: RegExpExecArray | null
    let idx = 0

    while ((match = regex.exec(line)) !== null) {
      const [full, str, num, bool, punct, space, other] = match
      if (str) {
        if (str.endsWith(':')) {
          const key = str.slice(0, -1)
          parts.push(<span key={idx++} className="hl-key">{key}</span>)
          parts.push(<span key={idx++} className="hl-punct">:</span>)
        } else {
          parts.push(<span key={idx++} className="hl-str">{str}</span>)
        }
      } else if (num) {
        parts.push(<span key={idx++} className="hl-num">{num}</span>)
      } else if (bool) {
        parts.push(<span key={idx++} className="hl-bool">{bool}</span>)
      } else if (punct) {
        parts.push(<span key={idx++} className="hl-punct">{punct}</span>)
      } else if (space) {
        parts.push(space)
      } else {
        parts.push(other || full)
      }
    }
    return parts.length ? parts : line
  }

  if (['ts', 'tsx', 'js', 'jsx'].includes(ext)) {
    const parts: React.ReactNode[] = []
    const regex = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(\b(?:import|export|from|default|function|const|let|var|return|if|else|switch|case|break|for|while|try|catch|finally|throw|class|extends|interface|type|async|await|new|this|typeof|instanceof)\b)|(\b(?:true|false|null|undefined|NaN)\b)|(-?\d+(?:\.\d+)?)|([{}()[\].,;:?])|(\s+)|([^\s"'`{}[\]().,;:?]+)/g
    let match: RegExpExecArray | null
    let idx = 0

    while ((match = regex.exec(line)) !== null) {
      const [full, comment, str, kw, bool, num, punct, space, other] = match
      if (comment) {
        parts.push(<span key={idx++} className="hl-comment">{comment}</span>)
      } else if (str) {
        parts.push(<span key={idx++} className="hl-str">{str}</span>)
      } else if (kw) {
        parts.push(<span key={idx++} className="hl-kw">{kw}</span>)
      } else if (bool) {
        parts.push(<span key={idx++} className="hl-bool">{bool}</span>)
      } else if (num) {
        parts.push(<span key={idx++} className="hl-num">{num}</span>)
      } else if (punct) {
        parts.push(<span key={idx++} className="hl-punct">{punct}</span>)
      } else if (space) {
        parts.push(space)
      } else {
        parts.push(other || full)
      }
    }
    return parts.length ? parts : line
  }

  return line
}

function CodeLines({ content, filename }: { content: string; filename: string }) {
  const lines = useMemo(() => content.split('\n'), [content])
  const [activeLine, setActiveLine] = useState<number | null>(() => (lines.length >= 5 ? 5 : lines.length > 0 ? 1 : null))
  const ext = useMemo(() => filename.split('.').pop()?.toLowerCase() ?? '', [filename])

  return (
    <div className="pw-codeview">
      <code className="pw-codelines">
        {lines.map((line, i) => {
          const lineNum = i + 1
          const isActive = activeLine === lineNum
          return (
            <div
              key={i}
              className={`pw-codeline${isActive ? ' active-line' : ''}`}
              onClick={() => setActiveLine(lineNum)}
            >
              <i className="pw-line-num">{lineNum}</i>
              <span className="pw-line-text">{highlightLine(line, ext)}</span>
            </div>
          )
        })}
      </code>
    </div>
  )
}

/* ── Share (Figma 19) — per-project collaborators, wired to the real sharing API ──────────────── */
function RequirementDialogLegacy({
  session, busy, error, onAnswer, onApprove,
}: {
  session: RequirementSession
  busy: boolean
  error: string
  onAnswer: (questionId: string, selectedOption: string | null, otherText: string | null) => Promise<void>
  onApprove: () => Promise<void>
}) {
  const firstIncomplete = session.questions.findIndex((question) =>
    question.required && !question.selectedOption && !question.otherText)
  const [index, setIndex] = useState(firstIncomplete >= 0 ? firstIncomplete : 0)
  const question = session.questions[index]
  const [selected, setSelected] = useState<string | null>(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((option) => option.recommended)?.id ?? null)
  const [other, setOther] = useState(question?.otherText ?? '')

  useEffect(() => {
    const next = session.questions.findIndex((item) => item.required && !item.selectedOption && !item.otherText)
    setIndex(next >= 0 ? next : Math.max(0, session.questions.length - 1))
  }, [session])

  useEffect(() => {
    setSelected(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((option) => option.recommended)?.id ?? null)
    setOther(question?.otherText ?? '')
  }, [question?.id, question?.selectedOption, question?.otherText, question?.recommendedOption, question?.options])

  if (!question) return null
  const isOther = selected === 'other'
  const answered = !!selected || !!other.trim()
  const last = index === session.questions.length - 1
  const saveAndContinue = async () => {
    await onAnswer(question.id, isOther ? 'other' : selected, isOther ? other : null)
    if (!last) setIndex((value) => Math.min(value + 1, session.questions.length - 1))
  }

  return createPortal(
    <div className="pw-modal-scrim pw-requirement-scrim">
      <div className="pw-modal pw-requirement-modal" role="dialog" aria-modal="true" aria-label="Project requirements">
        <header>
          <div><span className="pw-requirement-kicker">REQUIREMENTS</span><b>Shape the first version</b></div>
          <span className="pw-requirement-count">{index + 1} / {session.questions.length}</span>
        </header>
        <div className="pw-requirement-progress" aria-hidden="true">
          {session.questions.map((item, itemIndex) => <span key={item.id} className={itemIndex <= index ? 'active' : ''} />)}
        </div>
        <div className="pw-requirement-question">
          <h2>{question.prompt}</h2>
          <div className="pw-requirement-options">
            {question.options.map((option) => (
              <button type="button" key={option.id} className={'pw-requirement-option' + (selected === option.id ? ' selected' : '')}
                onClick={() => { setSelected(option.id); if (option.id !== 'other') setOther('') }} disabled={busy}>
                <span className="pw-requirement-radio">{selected === option.id ? '✓' : ''}</span>
                <span><b>{option.label}</b><small>{option.description}</small></span>
                {option.recommended && <em>Recommended</em>}
              </button>
            ))}
          </div>
          {isOther && <textarea className="pw-requirement-other" value={other} onChange={(event) => setOther(event.target.value)} placeholder="Tell us what you have in mind" rows={3} autoFocus />}
        </div>
        {error && <p className="pw-error">{error}</p>}
        <footer className="pw-requirement-actions">
          <button type="button" className="pw-requirement-back" onClick={() => setIndex((value) => Math.max(0, value - 1))} disabled={index === 0 || busy}>Back</button>
          {session.complete
            ? <button type="button" className="pw-share-send" onClick={() => void onApprove()} disabled={busy}>{busy ? 'Preparing design…' : 'Approve requirements'}</button>
            : <button type="button" className="pw-share-send" onClick={() => void saveAndContinue()} disabled={busy || !answered}>{busy ? 'Saving…' : last ? 'Save answer' : 'Continue'}</button>}
        </footer>
      </div>
    </div>, document.body,
  )
}

void RequirementDialogLegacy

function RequirementAnswerCard({ projectName, session, onPreview }: {
  projectName?: string
  session: RequirementSession
  onPreview: () => void
}) {
  const [expanded, setExpanded] = useState(true)
  return (
    <article className="pw-requirement-card">
      <header>
        <b>{projectName ? `Built ${projectName}` : 'Approved project requirements'}</b>
        <span aria-hidden="true">+</span>
      </header>
      {expanded && <div className="pw-requirement-card-body">
        {session.questions.map((question) => {
          const answer = question.otherText || question.options.find((option) => option.id === question.selectedOption)?.label || question.selectedOption
          return <div className="pw-requirement-card-row" key={question.id}><span>{question.prompt}</span><b>{answer ?? 'Not specified'}</b></div>
        })}
      </div>}
      <footer>
        <button type="button" onClick={() => setExpanded((value) => !value)}>{expanded ? 'Hide details' : 'Show details'}</button>
        <button type="button" onClick={onPreview}>Preview</button>
      </footer>
    </article>
  )
}

function RequirementWaiting() {
  return (
    <div className="pw-requirement-waiting" role="status" aria-live="polite">
      <span className="pw-waiting-mark"><i /></span>
      <span>Waiting for answers</span>
      <span className="pw-waiting-dots" aria-hidden="true"><i /><i /><i /></span>
    </div>
  )
}

function RequirementDialog({
  session, busy, error, onAnswer, onApprove, onSkipAll,
}: {
  session: RequirementSession
  busy: boolean
  error: string
  onAnswer: (questionId: string, selectedOption: string | null, otherText: string | null) => Promise<void>
  onApprove: () => Promise<void>
  onSkipAll: () => Promise<void>
}) {
  const firstIncomplete = session.questions.findIndex((question) =>
    question.required && !question.selectedOption && !question.otherText)
  const [index, setIndex] = useState(firstIncomplete >= 0 ? firstIncomplete : 0)
  const question = session.questions[index]
  const [selected, setSelected] = useState<string | null>(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((option) => option.recommended)?.id ?? null)
  const [other, setOther] = useState(question?.otherText ?? '')

  useEffect(() => {
    const next = session.questions.findIndex((item) => item.required && !item.selectedOption && !item.otherText)
    setIndex(next >= 0 ? next : Math.max(0, session.questions.length - 1))
  }, [session])

  useEffect(() => {
    setSelected(question?.selectedOption ?? question?.recommendedOption ?? question?.options.find((option) => option.recommended)?.id ?? null)
    setOther(question?.otherText ?? '')
  }, [question?.id, question?.selectedOption, question?.otherText, question?.recommendedOption, question?.options])

  if (!question) return null
  const last = index === session.questions.length - 1
  const isOther = selected === 'other'
  const answered = !!selected && (selected !== 'other' || !!other.trim())
  const visibleOptions = question.options.reduce<RequirementSession['questions'][number]['options']>((result, option) => {
    const custom = option.id === 'other' || /^(other|write your own|custom)/i.test(option.label.trim())
    if (custom) {
      if (!result.some((item) => item.id === 'other')) result.push({ ...option, id: 'other', label: 'Write your own...' })
    } else result.push(option)
    return result
  }, [])

  const choose = (optionId: string) => {
    setSelected(optionId)
    if (optionId !== 'other') setOther('')
  }

  const saveAndContinue = async () => {
    await onAnswer(question.id, isOther ? 'other' : selected, isOther ? other : null)
    if (last) await onApprove()
    else setIndex((value) => Math.min(value + 1, session.questions.length - 1))
  }

  return createPortal(
    <div className="pw-requirement-scrim">
      <div className="pw-requirement-modal" role="dialog" aria-modal="true" aria-label="Project requirements">
        <header className="pw-requirement-head">
          <h2>{question.prompt}</h2>
          <span className="pw-requirement-close" aria-hidden="true">x</span>
        </header>

        <div className="pw-requirement-progress" aria-label={`Question ${index + 1} of ${session.questions.length}`}>
          {session.questions.map((item, itemIndex) => (
            <span key={item.id} className={itemIndex <= index ? 'active' : ''} />
          ))}
        </div>

        <div className="pw-requirement-options">
          {visibleOptions.map((option) => option.id === 'other' ? (
            <div className={'pw-requirement-option-wrap' + (isOther ? ' selected' : '')} key={option.id}>
              <button type="button" className="pw-requirement-option pw-requirement-other-option"
                onClick={() => choose(option.id)} disabled={busy}>
                <span className="pw-requirement-radio">{isOther ? 'x' : ''}</span>
                <span><b>{option.label === 'Other' ? 'Write your own...' : option.label}</b></span>
              </button>
              {isOther && (
                <input className="pw-requirement-other-inline" value={other}
                  onChange={(event) => { setOther(event.target.value); setSelected('other') }}
                  onFocus={() => setSelected('other')} placeholder="Tell us what you have in mind"
                  aria-label="Your custom requirement" autoFocus disabled={busy} />
              )}
            </div>
          ) : (
            <button type="button" key={option.id}
              className={'pw-requirement-option' + (selected === option.id ? ' selected' : '')}
              onClick={() => choose(option.id)} disabled={busy}>
              <span className="pw-requirement-radio">{selected === option.id ? 'x' : ''}</span>
              <span className="pw-requirement-option-copy">
                <b>{option.label}</b>
                <small>{option.description}</small>
              </span>
              {option.recommended && <em>Recommended</em>}
            </button>
          ))}
        </div>

        {error && <p className="pw-error">{error}</p>}
        <footer className="pw-requirement-actions">
          <div className="pw-requirement-nav">
            <button type="button" className="pw-requirement-icon-button" aria-label="Previous question" title="Previous question"
              onClick={() => setIndex((value) => Math.max(0, value - 1))} disabled={index === 0 || busy}>‹</button>
            <button type="button" className="pw-requirement-icon-button" aria-label="Next question" title="Next question"
              onClick={() => setIndex((value) => Math.min(session.questions.length - 1, value + 1))} disabled={last || busy}>›</button>
          </div>
          <button type="button" className="pw-requirement-skip" onClick={() => void onSkipAll()} disabled={busy}>Skip all</button>
          <button type="button" className="pw-requirement-next" onClick={() => void saveAndContinue()} disabled={busy || !answered}>
            {busy ? 'Saving...' : last ? 'Submit' : 'Next'}
          </button>
        </footer>
      </div>
    </div>, document.body,
  )
}

function ShareDialog({ projectId, onClose }: { projectId: string; onClose: () => void }) {
  const { data: members, isLoading } = useProjectMembers(projectId)
  const share = useShareProject(projectId)
  const updateRole = useUpdateProjectMemberRole(projectId)
  const removeMember = useRemoveProjectMember(projectId)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<ProjectRole>('VIEWER')
  const [error, setError] = useState('')
  const list = members ?? []

  const submit = async () => {
    if (!email.trim()) return
    setError('')
    try { await share.mutateAsync({ email: email.trim(), role }); setEmail('') }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not share the project.') }
  }

  return (
    <div className="pw-modal-scrim" onClick={onClose}>
      <div className="pw-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Share this project">
        <header><b>Share this project</b><button className="pw-modal-x" onClick={onClose} aria-label="Close">✕</button></header>
        <p className="pw-modal-sub">Invite people by email to view or edit. Collaborators see it in their projects.</p>
        <div className="pw-share-invite">
          <input type="email" placeholder="name@company.com" value={email} onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void submit() } }} />
          <select value={role} onChange={(e) => setRole(e.target.value as ProjectRole)}>
            <option value="VIEWER">Can view</option><option value="EDITOR">Can edit</option>
          </select>
          <button className="pw-share-send" onClick={() => void submit()} disabled={share.isPending}>{share.isPending ? 'Sharing…' : 'Share'}</button>
        </div>
        {error && <p className="pw-error">{error}</p>}
        <div className="pw-share-list">
          {isLoading ? <p className="pw-loading">Loading…</p>
            : list.length ? list.map((m) => (
              <div className="pw-share-row" key={m.id}>
                <span className="pw-share-avatar">{(m.name || m.email || '?')[0].toUpperCase()}</span>
                <span className="pw-share-id"><b>{m.name || m.email}</b><small>{m.email}</small></span>
                <select value={m.role} onChange={(e) => updateRole.mutate({ memberId: m.id, role: e.target.value as ProjectRole })} disabled={updateRole.isPending}>
                  <option value="VIEWER">Can view</option><option value="EDITOR">Can edit</option><option value="OWNER">Owner</option>
                </select>
                <button className="pw-share-remove" onClick={() => removeMember.mutate(m.id)} disabled={removeMember.isPending} aria-label={`Remove ${m.email ?? 'member'}`}>✕</button>
              </div>
            )) : <p className="pw-share-empty">Not shared with anyone yet — invite someone above.</p>}
        </div>
      </div>
    </div>
  )
}

/* ── Publish (Figma 20) — real publish lifecycle → public URL ─────────────────────────────────── */
function PublishDialog({ project, onClose, onChange }: { project: Project; onClose: () => void; onChange: (p: Project) => void }) {
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

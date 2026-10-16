import { useEffect, useMemo, useRef, useState } from 'react'
import { type AgentRun, type AgentStep, type Message, type MessageFeedback, type Project, type ProjectType, type RequirementSession } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { useShellMenu } from '../../../rendra/shell/ShellMenu'
import { RequirementAnswerCard, RequirementWaiting } from '../dialogs/RequirementDialog'
import { needsRequirementInterview } from '../runUtils'
import type { LayoutMode, Quote } from '../workspaceTypes'
import { BuildStatus } from './BuildStatus'
import { ChatMessage } from './ChatMessage'
import { Composer } from './Composer'
import { orderMessages } from './orderMessages'
import { ProjectMenu } from './ProjectMenu'
import { SelectionReplyPopup } from './SelectionReplyPopup'
import './chat.css'


type ChatColumnProps = {
  project: Project | null
  messages: Message[]
  runs: AgentRun[]
  stepsByRun: Record<string, AgentStep[]>
  loading: boolean
  hasOlder: boolean
  loadingOlder: boolean
  onLoadOlder: () => void
  requirements: RequirementSession | null
  rollbackError?: string
  sendError?: string
  editRequest: { text: string; nonce: number } | null
  onSend: (prompt: string, mode: ProjectType, files?: File[], attachmentPaths?: string[], proposalId?: string) => Promise<void>
  onFeedback: (messageId: string, feedback: MessageFeedback | null) => void
  onEdit: (text: string) => void
  onOpenDetails: (runId?: string) => void
  onOpenPreview: () => void
  onOpenThinking: (reasoning: string, seconds: number) => void
  /** A failed build was retried from its draft. */
  onRetried?: (run: AgentRun) => void
  /** The project was renamed. */
  onProjectChange: (project: Project) => void
  layoutMode: LayoutMode
  chatWidth: number
  dragging: boolean
  onToggleFocus: () => void
}

/** Left side of the workspace: project header, the conversation, and the composer. */
export function ChatColumn({
  project, messages, runs, stepsByRun, loading, hasOlder, loadingOlder, onLoadOlder,
  requirements, rollbackError, sendError, editRequest, onSend, onFeedback, onEdit,
  onOpenDetails, onOpenPreview, onOpenThinking, onRetried, onProjectChange, layoutMode, chatWidth, dragging, onToggleFocus,
}: ChatColumnProps) {
  const [quote, setQuote] = useState<Quote | null>(null)
  const shell = useShellMenu()
  const bottomRef = useRef<HTMLDivElement>(null)
  const chatInnerRef = useRef<HTMLDivElement>(null)
  const ordered = useMemo(() => orderMessages(messages), [messages])
  const latestSavedRun = useMemo(() => [...runs].filter(run => run.codeSavedAt).sort((a, b) => Date.parse(b.codeSavedAt!) - Date.parse(a.codeSavedAt!))[0]?.id, [runs])

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])

  return (
    <section className={'ws-chat' + (layoutMode === 'preview-only' ? ' ws-chat--hidden' : '')} style={{
      width: layoutMode === 'split' ? chatWidth : undefined,
      flex: layoutMode === 'chat-only' ? '1 1 auto' : undefined,
      transition: dragging ? 'none' : 'flex-basis .3s cubic-bezier(.16,1,.3,1), width .18s ease, opacity .25s ease',
    }}>
      <header className="ws-chat-head">
        {shell.compact && (
          <button type="button" className="ws-flat-btn" aria-label="Open menu" title="Menu" onClick={shell.openMenu}>
            <AppIcon name="menu" />
          </button>
        )}
        <ProjectMenu project={project} onRenamed={onProjectChange} />
        <BuildStatus runs={runs} />
        <span className="ws-grow" />
        <button
          type="button"
          className="ws-flat-btn"
          onClick={onToggleFocus}
          aria-label={layoutMode === 'chat-only' ? 'Show panel' : 'Focus chat'}
          title={layoutMode === 'chat-only' ? 'Show panel' : 'Focus chat'}
        >
          <AppIcon name="sidebar" />
        </button>
      </header>
      <SelectionReplyPopup containerRef={chatInnerRef} onQuote={setQuote} />
      <div className="ws-chat__scroll">
        <div className="ws-chat__inner" ref={chatInnerRef}>
          {!loading && hasOlder && (
            <button type="button" className="ws-chat__older" onClick={onLoadOlder} disabled={loadingOlder}>
              {loadingOlder ? 'Loading…' : 'Load older messages'}
            </button>
          )}
          {loading
            ? <p className="ws-chat__note">Loading conversation…</p>
            : ordered.map((m) => {
                const msgRun = m.role === 'ASSISTANT' ? runs.find((r) => r.messageId === m.id) : undefined
                const msgSteps = msgRun ? stepsByRun[msgRun.id] ?? [] : []
                return (
                  <ChatMessage key={m.id} message={m} run={msgRun} steps={msgSteps} currentPreview={msgRun?.id === latestSavedRun}
                    projectId={project?.id} onRetried={onRetried}
                    onOpenDetails={() => onOpenDetails(msgRun?.id)} onOpenPreview={onOpenPreview} onOpenThinking={onOpenThinking}
                    onFeedback={(feedback) => onFeedback(m.id, feedback)} onEdit={onEdit}
                    onBuild={(action) => onSend(action.message, action.mode, [], action.attachments, action.proposalId)} />
                )
              })}
          {rollbackError && <p className="ws-chat__error">{rollbackError}</p>}
          {sendError && <p className="ws-chat__error" role="alert">{sendError}</p>}
          {requirements?.complete && <RequirementAnswerCard projectName={project?.name} session={requirements} onPreview={onOpenPreview} />}
          {project?.lifecycleStage === 'REQUIREMENTS' && needsRequirementInterview(project) && <RequirementWaiting />}
          <div ref={bottomRef} />
        </div>
      </div>
      <Composer project={project} onSend={onSend} quote={quote} onClearQuote={() => setQuote(null)}
        editRequest={editRequest} />
    </section>
  )
}

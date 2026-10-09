import { useState } from 'react'
import { type AgentRun, type AgentStep, type Message, type MessageFeedback } from '../../../api/projects'
import { Ico } from '../../../rendra/parts'
import { BRAIN_ICON, CHECK_ICON, COPY_ICON, EDIT_ICON, FILE_ICON } from '../icons'
import { changedFileCount, combinedReasoning, formatMessageTime, hasBuildActivity, isActive, secondsBetween } from '../runUtils'
import { Markdown } from './Markdown'
import { MessageActions } from './MessageActions'
import { BuildProgress, ThinkingLine } from './RunProgress'

/** One turn of the conversation: an assistant reply (thinking / build progress while pending) or a user message. */
export function ChatMessage({ message, run, steps, onOpenDetails, onOpenPreview, onOpenThinking, onFeedback, onEdit }: {
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
    <article className="msg msg--agent" data-message-id={message.id} data-role="ASSISTANT">
      {pending
        ? (building ? <BuildProgress run={run!} steps={steps} /> : <ThinkingLine since={run?.createdAt ?? message.createdAt} />)
        : (
          <>
            {settled && run && (
              <div className="msg__chips">
                {reasoning && (
                  <button type="button" className="msg-chip" onClick={() => onOpenThinking(reasoning, seconds)}>
                    <Ico d={BRAIN_ICON} size={13} />
                    Thought for {seconds}s
                  </button>
                )}
                {changedFiles > 0 && (
                  <button type="button" className="msg-chip" onClick={onOpenDetails}>
                    <Ico d={FILE_ICON} size={13} />
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
    <div className="msg msg--user" data-message-id={message.id} data-role="USER">
      <article className="msg__bubble">{message.content}</article>
      <div className="msg__actions msg__actions--on-hover">
        <button type="button" className="msg-icon" onClick={() => void copy()}
          title={copied ? 'Copied' : 'Copy'} aria-label="Copy message">
          <Ico d={copied ? CHECK_ICON : COPY_ICON} size={13} />
        </button>
        <button type="button" className="msg-icon" onClick={() => onEdit(message.content)}
          title="Edit" aria-label="Edit message">
          <Ico d={EDIT_ICON} size={13} />
        </button>
        <span className="msg__time">{formatMessageTime(message.createdAt)}</span>
      </div>
    </div>
  )
}

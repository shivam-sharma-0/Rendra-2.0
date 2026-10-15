import { useState } from 'react'
import { type AgentRun, type AgentStep, type Message, type MessageFeedback, type SuggestedAction } from '../../../api/projects'
import { Ico } from '../../../rendra/parts'
import { CHECK_ICON, COPY_ICON, EDIT_ICON } from '../icons'
import { formatMessageTime, isActive } from '../runUtils'
import { Markdown } from './Markdown'
import { MessageActions } from './MessageActions'
import { PlanApproval } from './PlanApproval'
import { ThinkingLine } from './RunProgress'
import { BuildReplyCard } from './BuildReplyCard'
import { BuildSuggestion } from './BuildSuggestion'
import { buildReply, readableBuildText } from './buildReply'

/** One turn of the conversation: an assistant reply (thinking / build progress while pending) or a user message. */
export function ChatMessage({ message, run, currentPreview = true, onOpenDetails, onOpenPreview, onFeedback, onEdit, onBuild }: {
  message: Message; run?: AgentRun; steps: AgentStep[]
  currentPreview?: boolean
  onOpenDetails: () => void; onOpenPreview: () => void
  onOpenThinking: (reasoning: string, seconds: number) => void
  onFeedback: (feedback: MessageFeedback | null) => void
  onEdit: (content: string) => void
  onBuild?: (action: SuggestedAction) => Promise<void>
}) {
  if (message.role === 'USER') return <UserMessage message={message} onEdit={onEdit} />
  const pending = message.status === 'PENDING' || (message.status === 'STREAMING' && !message.content)
  const active = !!run && isActive(run)
  const build = run?.mode === 'BUILD' && !run.conversational
  const content = build ? readableBuildText(message.content) : message.content

  return (
    <article className="msg msg--agent" data-message-id={message.id} data-role="ASSISTANT">
      {build && run && <BuildReplyCard run={run} currentPreview={currentPreview} onOpenDetails={onOpenDetails} onOpenPreview={onOpenPreview} />}
      {pending || (build && active)
        ? (!build && <ThinkingLine since={run?.createdAt ?? message.createdAt} queued={run?.status === 'QUEUED'} />)
        : (
          <>
            {(!build || (run?.status === 'DONE' && run.codeSavedAt && content)) && <Markdown content={content} />}
            {run?.mode === 'PLAN' && run.status === 'DONE' && run.planAvailable && <PlanApproval run={run} />}
            {message.status === 'COMPLETE' && message.suggestedAction && !active
              && <BuildSuggestion action={message.suggestedAction} onBuild={onBuild} />}
            <MessageActions message={{ ...message, content: build ? (run?.status === 'DONE' ? content : run ? buildReply(run).text : '') : message.content }} run={run} hasPreview={currentPreview && !!run && buildReply(run).preview}
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

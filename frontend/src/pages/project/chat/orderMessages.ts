import { type Message } from '../../../api/projects'
import { isInternalWorkflowMessage } from '../runUtils'

/**
 * The conversation as shown: each user message followed by its assistant reply, in the order the
 * user messages were sent, then any reply not yet linked to a user message (still streaming).
 * System and internal workflow messages are left out.
 */
export function orderMessages(messages: Message[]): Message[] {
  const nonSystem = messages.filter((m) => m.role !== 'SYSTEM' && !isInternalWorkflowMessage(m))
  // Backend canonical order: createdAt, then seq (seq resets on restart, so it is only a
  // tie-breaker). A row without createdAt has not been read back from the DB yet, so it
  // is the newest — never let it fall to new Date(null) = 1970 and jump to the top.
  const sortTime = (m: Message) => (m.createdAt ? new Date(m.createdAt).getTime() : Number.MAX_SAFE_INTEGER)
  const userMsgs = nonSystem
    .filter((m) => m.role === 'USER')
    .sort((a, b) => (sortTime(a) - sortTime(b)) || ((a.seq ?? 0) - (b.seq ?? 0)))
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
  return ordered
}

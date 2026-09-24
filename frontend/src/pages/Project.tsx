import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { messageApi, projectApi, type Message, type Project } from '../api/projects'
import Logo from '../components/Logo'
import PromptBar from '../components/PromptBar'
import { useRealtime, type RealtimeMessage } from '../realtime/useRealtime'

export default function ProjectPage() {
  const { id = '' } = useParams()
  const [project, setProject] = useState<Project | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  // Initial load: project + conversation.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    ;(async () => {
      try {
        const [p, page] = await Promise.all([projectApi.get(id), messageApi.list(id)])
        if (cancelled) return
        setProject(p)
        setMessages(page.content)
      } catch {
        if (!cancelled) setNotFound(true)
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [id])

  // Upsert a message by id (insert new, or replace in place).
  const upsert = useCallback((incoming: Message) => {
    setMessages((prev) => {
      const i = prev.findIndex((m) => m.id === incoming.id)
      if (i === -1) return [...prev, incoming]
      const next = prev.slice()
      next[i] = incoming
      return next
    })
  }, [])

  // Realtime: fill the streaming assistant reply as tokens/updates arrive.
  const onRealtime = useCallback(
    (msg: RealtimeMessage) => {
      const payload = msg.payload as (Partial<Message> & { id?: string; delta?: string }) | undefined
      if (!payload?.id || payload.projectId !== id) return

      if (msg.type === 'MESSAGE_DELTA' && typeof payload.delta === 'string') {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === payload.id ? { ...m, content: m.content + payload.delta, status: 'STREAMING' } : m,
          ),
        )
      } else if (msg.type === 'MESSAGE_UPSERT') {
        upsert(payload as Message)
      }
    },
    [id, upsert],
  )

  useRealtime({ enabled: !!project, projectId: id, onMessage: onRealtime })

  // Keep the latest message in view.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const send = useCallback(
    async (prompt: string, mode: 'BUILD' | 'CHAT' | 'PLAN') => {
      try {
        const created = await messageApi.post(id, prompt, mode)
        setMessages((prev) => [...prev, ...created])
      } catch {
        /* surfaced elsewhere; keep the composer usable */
      }
    },
    [id],
  )

  if (notFound) {
    return (
      <div className="grid min-h-screen place-items-center px-5 text-center">
        <div>
          <p className="text-sm text-muted">This project doesn’t exist or isn’t yours.</p>
          <Link to="/dashboard" className="mt-3 inline-block text-sm font-semibold text-accent hover:underline">
            ← Back to dashboard
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen flex-col">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-border px-5 py-3">
        <Link to="/dashboard" className="flex items-center gap-2 text-muted transition hover:text-text">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M15 18l-6-6 6-6" />
          </svg>
          <Logo showWordmark={false} />
        </Link>
        <span className="truncate px-4 text-sm font-semibold text-text">{project?.name ?? 'Loading…'}</span>
        <span className="w-8" />
      </header>

      {/* Conversation */}
      <main className="mx-auto w-full max-w-3xl flex-1 space-y-4 overflow-y-auto px-5 py-8">
        {loading ? (
          <p className="py-16 text-center text-[13px] text-faint">Loading conversation…</p>
        ) : (
          messages.filter((m) => m.role !== 'SYSTEM').map((m) => <Bubble key={m.id} message={m} />)
        )}
        <div ref={bottomRef} />
      </main>

      {/* Composer */}
      <div className="border-t border-border px-5 py-4">
        <div className="mx-auto w-full max-w-3xl">
          <PromptBar onSubmit={send} placeholder="Reply to Rendra…" />
        </div>
      </div>
    </div>
  )
}

function Bubble({ message }: { message: Message }) {
  const isUser = message.role === 'USER'
  const pending = message.status === 'PENDING' || (message.status === 'STREAMING' && !message.content)

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div
        className={
          'max-w-[85%] rounded-2xl px-4 py-2.5 text-[14px] leading-relaxed ' +
          (isUser
            ? 'bg-[color:var(--accent)] text-[color:var(--accent-contrast)]'
            : 'border border-border bg-[color:var(--surface)] text-text')
        }
      >
        {pending ? (
          <span className="inline-flex gap-1">
            <Dot /> <Dot /> <Dot />
          </span>
        ) : (
          <span className="whitespace-pre-wrap">{message.content}</span>
        )}
      </div>
    </div>
  )
}

function Dot() {
  return <span className="animate-pulse-soft inline-block h-1.5 w-1.5 rounded-full bg-current" />
}

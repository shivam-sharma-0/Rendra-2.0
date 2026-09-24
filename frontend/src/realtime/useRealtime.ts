import { useCallback, useEffect, useRef, useState } from 'react'
import { session } from '../auth/api'

/** One frame on the realtime socket — mirrors the backend {@code RealtimeMessage} envelope. */
export type RealtimeMessage = { type: string; payload?: unknown }

export type RealtimeStatus = 'connecting' | 'open' | 'closed'

type Options = {
  /** The project room to join — the socket only opens once this is known. */
  projectId?: string
  /** Gate the connection (e.g. only when authenticated). Defaults to true. */
  enabled?: boolean
  /** Called for every inbound frame except the internal PONG heartbeat reply. */
  onMessage?: (msg: RealtimeMessage) => void
  /** Notified whenever the connection status flips. */
  onStatusChange?: (status: RealtimeStatus) => void
}

const PING_INTERVAL_MS = 25_000 // keep proxies/load-balancers from idling the socket out
const MAX_BACKOFF_MS = 30_000

/** Build a same-origin ws(s):// URL so dev goes through the Vite proxy and prod hits its own host. */
function realtimeUrl(token: string, projectId: string): string {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/realtime?token=${encodeURIComponent(token)}&projectId=${encodeURIComponent(projectId)}`
}

/**
 * Subscribes to the backend {@code /realtime} WebSocket for the current user.
 *
 * <p>Auth rides the URL (the browser can't set headers on a WS handshake); the backend
 * verifies the token before the socket opens. Handles auto-reconnect with capped
 * exponential backoff and a heartbeat, and tears everything down on unmount. Callbacks are
 * held in refs so re-renders never churn the connection — the socket only re-opens when
 * {@code enabled} flips.
 *
 * @returns the live {@link RealtimeStatus} and a {@code send()} for client→server frames.
 */
export function useRealtime({ projectId, enabled = true, onMessage, onStatusChange }: Options = {}) {
  const [status, setStatus] = useState<RealtimeStatus>('closed')

  const socketRef = useRef<WebSocket | null>(null)
  const onMessageRef = useRef(onMessage)
  const onStatusRef = useRef(onStatusChange)
  onMessageRef.current = onMessage
  onStatusRef.current = onStatusChange

  const setPhase = useCallback((s: RealtimeStatus) => {
    setStatus(s)
    onStatusRef.current?.(s)
  }, [])

  const send = useCallback((msg: RealtimeMessage): boolean => {
    const ws = socketRef.current
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg))
      return true
    }
    return false
  }, [])

  useEffect(() => {
    if (!enabled || !projectId) return

    let closedByUs = false
    let backoff = 1_000
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let pingTimer: ReturnType<typeof setInterval> | undefined

    const connect = () => {
      const token = session.access()
      if (!token || !projectId) return // nothing to auth with; a later mount will retry

      setPhase('connecting')
      const ws = new WebSocket(realtimeUrl(token, projectId))
      socketRef.current = ws

      ws.onopen = () => {
        backoff = 1_000 // reset on a clean open
        setPhase('open')
        pingTimer = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'PING' }))
        }, PING_INTERVAL_MS)
      }

      ws.onmessage = (e) => {
        let msg: RealtimeMessage
        try {
          msg = JSON.parse(e.data as string)
        } catch {
          return
        }
        if (msg.type === 'PONG') return // heartbeat ack — swallow it
        onMessageRef.current?.(msg)
      }

      ws.onclose = () => {
        if (pingTimer) clearInterval(pingTimer)
        socketRef.current = null
        setPhase('closed')
        if (closedByUs) return
        // Reconnect with capped exponential backoff.
        reconnectTimer = setTimeout(connect, backoff)
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
      }

      ws.onerror = () => ws.close() // funnel every failure through onclose → one reconnect path
    }

    connect()

    return () => {
      closedByUs = true
      if (reconnectTimer) clearTimeout(reconnectTimer)
      if (pingTimer) clearInterval(pingTimer)
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [enabled, projectId, setPhase])

  return { status, send }
}

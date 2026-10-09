import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { type MessageRole } from '../../../api/projects'
import { Ico } from '../../../rendra/parts'
import { REPLY_ICON } from '../icons'
import type { Quote } from '../workspaceTypes'

/** Selecting text inside any message reveals a floating "Reply" pill above the selection; clicking it
 * quotes that exact snippet into the composer so the next prompt can target it precisely. Rendered as
 * a portal so it is never clipped by the chat list's overflow:auto. */
export function SelectionReplyPopup({ containerRef, onQuote }: {
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
    <button ref={popRef} type="button" className="msg-reply-pop" style={{ top: popup.top, left: popup.left }}
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

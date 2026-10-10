import { useCallback, useEffect, useRef, useState } from 'react'
import { useMediaQuery } from '../../rendra/shell/useMediaQuery'
import type { LayoutMode } from './workspaceTypes'
import { COLLAPSE_THRESHOLD, MIN_CHAT_WIDTH, MIN_PANEL_WIDTH, clamp, readStoredChatWidth, readStoredLayoutMode } from './layout'

/**
 * Chat / panel split: which side is shown, the chat width (dragging the divider; dragging past an
 * edge collapses that side), both remembered in localStorage. On a narrow screen only one side
 * shows at a time: a remembered split opens as chat-only (`narrow` tells the page to switch sides
 * instead of splitting).
 */
/** One side at a time below this width. */
const NARROW_QUERY = '(max-width: 860px)'

export function useSplitLayout() {
  const [storedMode, setLayoutMode] = useState<LayoutMode>(readStoredLayoutMode)
  const narrow = useMediaQuery(NARROW_QUERY)
  const layoutMode: LayoutMode = narrow && storedMode === 'split' ? 'chat-only' : storedMode
  const [chatWidth, setChatWidth] = useState<number>(readStoredChatWidth)
  const [dragging, setDragging] = useState(false)
  const splitRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    try { localStorage.setItem('pw-layout-mode', storedMode) } catch { /* ignore */ }
  }, [storedMode])
  useEffect(() => {
    try { localStorage.setItem('pw-chat-width', String(chatWidth)) } catch { /* ignore */ }
  }, [chatWidth])

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

  return { layoutMode, setLayoutMode, narrow, chatWidth, dragging, splitRef, resizePointerDown, resizePointerMove, resizePointerUp }
}

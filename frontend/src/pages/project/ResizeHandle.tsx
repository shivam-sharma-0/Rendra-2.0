import { useRef, type PointerEvent } from 'react'
import { cx } from '../../lib/cx'
import type { LayoutMode } from './workspaceTypes'

/** Pointer travel (px) below which a press on the divider counts as a click, not a drag. */
const CLICK_SLOP = 4

/**
 * The divider between chat and panel. Drag to resize (dragging past an edge hides that side);
 * when one side is hidden it becomes an edge that brings the split back on click or drag.
 */
export function ResizeHandle({ layoutMode, dragging, onRestoreSplit, onPointerDown, onPointerMove, onPointerUp }: {
  layoutMode: LayoutMode
  dragging: boolean
  onRestoreSplit: () => void
  onPointerDown: (e: PointerEvent<HTMLDivElement>) => void
  onPointerMove: (e: PointerEvent) => void
  onPointerUp: (e: PointerEvent) => void
}) {
  const pressX = useRef<number | null>(null)
  const chatHidden = layoutMode === 'preview-only'
  const panelHidden = layoutMode === 'chat-only'
  const hint = panelHidden ? 'Open panel' : chatHidden ? 'Open chat' : 'Drag to resize'

  return (
    <div
      className={cx('ws-gap', dragging && 'is-dragging', panelHidden && 'ws-gap--panel-hidden', chatHidden && 'ws-gap--chat-hidden')}
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize chat panel"
      title={dragging ? undefined : hint}
      onPointerDown={(e) => {
        pressX.current = e.clientX
        if (panelHidden || chatHidden) onRestoreSplit()
        onPointerDown(e)
      }}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onClick={(e) => {
        // The click that ends a drag must not undo it (a drag to an edge hides that side).
        const dragged = pressX.current !== null && Math.abs(e.clientX - pressX.current) > CLICK_SLOP
        pressX.current = null
        if (!dragged && (panelHidden || chatHidden)) onRestoreSplit()
      }}
    >
      <span className="ws-gap__grip" aria-hidden />
    </div>
  )
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { type AgentRun, type Message, type MessageFeedback } from '../../../api/projects'
import { cx } from '../../../lib/cx'
import { Ico } from '../../../rendra/parts'
import { CHECK_ICON, COPY_ICON, THUMBS_DOWN_ICON, THUMBS_UP_ICON } from '../icons'
import { creditsUsed, formatDuration } from '../runUtils'

const MORE_ICON = 'M5 12h.01M12 12h.01M19 12h.01'

/** Copy, feedback and a "more" menu (preview, time, credits) under an assistant reply. */
export function MessageActions({ message, run, hasPreview, onOpenPreview, onFeedback }: {
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
    <div className="msg__actions">
      <button type="button" className={cx('msg-icon', copied && 'is-copied')} onClick={() => void copy()}
        title={copied ? 'Copied' : 'Copy'} aria-label="Copy response">
        <Ico d={copied ? CHECK_ICON : COPY_ICON} size={14} />
      </button>
      <button type="button" className={cx('msg-icon', message.feedback === 'UP' && 'is-active')}
        onClick={() => toggleFeedback('UP')} title="Good response" aria-label="Good response">
        <Ico d={THUMBS_UP_ICON} size={14} />
      </button>
      <button type="button" className={cx('msg-icon', message.feedback === 'DOWN' && 'is-active')}
        onClick={() => toggleFeedback('DOWN')} title="Bad response" aria-label="Bad response">
        <Ico d={THUMBS_DOWN_ICON} size={14} />
      </button>
      {run && (
        <div className="msg-menu-anchor">
          <button ref={menuBtnRef} type="button" className="msg-icon"
            onClick={() => (menuOpen ? closeMenu() : openMenu())} aria-label="More actions" aria-expanded={menuOpen}>
            <Ico d={MORE_ICON} size={16} sw={3} />
          </button>
          {menuOpen && menuPos && createPortal(
            <>
              <div className="msg-menu-scrim" onClick={closeMenu} />
              <div className="msg-menu" style={{
                left: menuPos.left,
                top: menuPos.openUpward ? undefined : menuPos.top + 6,
                bottom: menuPos.openUpward ? window.innerHeight - menuPos.top + 6 : undefined,
              }}>
                <button type="button" className="msg-menu__item" disabled={!hasPreview}
                  onClick={() => { closeMenu(); onOpenPreview() }} title={hasPreview ? undefined : 'Nothing was built in this reply'}>
                  Preview
                </button>
                <div className="msg-menu__sep" />
                <div className="msg-menu__row"><span>Worked for</span><b>{formatDuration(run)}</b></div>
                <div className="msg-menu__row"><span>Credits used</span><b>{creditsUsed(run)}</b></div>
              </div>
            </>,
            document.body,
          )}
        </div>
      )}
    </div>
  )
}

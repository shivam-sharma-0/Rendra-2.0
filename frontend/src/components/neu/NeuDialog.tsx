import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cx } from '../../lib/cx'
import { useEscape } from '../../lib/useEscape'
import { AppIcon } from '../icons'
import NeuIconButton from './NeuIconButton'

type Props = {
  title: ReactNode
  /** One line under the title. */
  description?: ReactNode
  onClose: () => void
  children: ReactNode
  /** Buttons along the bottom edge. */
  footer?: ReactNode
  /** Width preset: sm 400 · md 480 (default) · lg 720. */
  size?: 'sm' | 'md' | 'lg'
  /** Hide the close button and ignore Escape / backdrop clicks (for a step that must be finished). */
  locked?: boolean
  className?: string
}

/**
 * Modal dialog on a blurred backdrop: raised panel with a title, optional description, body and
 * footer. Escape and a click outside close it; focus moves into the dialog on open and back to
 * whatever had it on close. Rendered into <body>.
 */
export default function NeuDialog({ title, description, onClose, children, footer, size = 'md', locked = false, className }: Props) {
  const titleId = useId()
  const descriptionId = useId()
  const panelRef = useRef<HTMLElement>(null)
  useEscape(onClose, !locked)

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const panel = panelRef.current
    const first = panel?.querySelector<HTMLElement>('input, select, textarea, button:not([data-dialog-close]), [href]')
    ;(first ?? panel)?.focus()
    return () => previous?.focus?.()
  }, [])

  return createPortal(
    <div className="neu-dialog-backdrop" onMouseDown={(e) => { if (!locked && e.target === e.currentTarget) onClose() }}>
      <section
        ref={panelRef}
        tabIndex={-1}
        className={cx('neu-dialog neu-panel', `neu-dialog--${size}`, className)}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
      >
        <header className="neu-dialog__head">
          <div>
            <h2 id={titleId}>{title}</h2>
            {description && <p id={descriptionId}>{description}</p>}
          </div>
          {!locked && (
            <NeuIconButton aria-label="Close" data-dialog-close onClick={onClose}>
              <AppIcon name="close" />
            </NeuIconButton>
          )}
        </header>
        <div className="neu-dialog__body">{children}</div>
        {footer && <footer className="neu-dialog__foot">{footer}</footer>}
      </section>
    </div>,
    document.body,
  )
}

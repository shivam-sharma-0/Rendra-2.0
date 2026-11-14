import { createElement, isValidElement, useEffect, useRef, useSyncExternalStore, type ElementType, type ReactNode } from 'react'
import { CircleAlert, CircleCheck, Info, TriangleAlert, X } from 'lucide-react'

export type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger'

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children?: ReactNode }) {
  return <span className={`app-badge app-badge--${tone}`}>{children}</span>
}

export function Notice({ tone = 'accent', title, children }: { tone?: Tone; title?: ReactNode; children?: ReactNode }) {
  return <div className={`app-notice app-notice--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
    {title && <strong>{title}</strong>}
    {children && <div>{children}</div>}
  </div>
}

export function iconNode(icon: ReactNode | ElementType, size = 22): ReactNode {
  return typeof icon === 'function' || (icon !== null && typeof icon === 'object' && !isValidElement(icon) && !Array.isArray(icon) && '$$typeof' in icon)
    ? createElement(icon as ElementType, { size }) : icon as ReactNode
}

export function EmptyState({ icon = '✦', title, description, action }: {
  icon?: ReactNode | ElementType; title: ReactNode; description?: ReactNode; action?: ReactNode
}) {
  return <div className="app-empty">
    <span className="app-empty__icon" aria-hidden="true">{iconNode(icon)}</span>
    <h3>{title}</h3>
    {description && <p className="app-muted">{description}</p>}
    {action}
  </div>
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return <div className="app-skeleton" aria-busy="true" aria-label="Loading">
    {Array.from({ length: lines }, (_, index) => <span key={index} style={{ width: `${92 - index * 14}%` }} />)}
  </div>
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <span className="app-spinner" role="status"><span className="app-visually-hidden">{label}</span></span>
}

export function ProgressBar({ value, max = 100, label }: { value: number; max?: number; label?: ReactNode }) {
  const percent = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0
  return <div className="app-progress">
    {label && <div className="app-progress__label"><span>{label}</span><span>{Math.round(percent)}%</span></div>}
    <div className="app-progress__track" role="progressbar" aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
      <span style={{ width: `${percent}%` }} />
    </div>
  </div>
}

export function Modal({ open, title, onClose, children, actions }: {
  open: boolean; title: ReactNode; onClose: () => void; children?: ReactNode; actions?: ReactNode
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) element.showModal()
    if (!open && element.open) element.close()
  }, [open])
  return <dialog ref={dialog} className="app-modal" onClose={onClose} onCancel={onClose}>
    <div className="app-modal__head">
      <h2>{title}</h2>
      <button type="button" className="app-modal__close" aria-label="Close" onClick={onClose}>×</button>
    </div>
    <div className="app-stack">{children}</div>
    {actions && <div className="app-modal__actions">{actions}</div>}
  </dialog>
}

type ToastItem = { id: number; tone: Tone; message: ReactNode }
let toasts: ToastItem[] = []
let lastToast = 0
const toastListeners = new Set<() => void>()
const announce = () => toastListeners.forEach(listener => listener())

export function dismissToast(id: number) {
  toasts = toasts.filter(item => item.id !== id)
  announce()
}

export function toast(message: ReactNode, tone: Tone = 'success') {
  const id = ++lastToast
  toasts = [...toasts.slice(-2), { id, tone, message }]
  announce()
  window.setTimeout(() => dismissToast(id), 4000)
  return id
}

function subscribeToasts(listener: () => void) {
  toastListeners.add(listener)
  return () => { toastListeners.delete(listener) }
}

const TOAST_ICONS = { neutral: Info, accent: Info, success: CircleCheck, warning: TriangleAlert, danger: CircleAlert }

export function Toaster() {
  const items = useSyncExternalStore(subscribeToasts, () => toasts, () => toasts)
  return <div className="app-toaster" aria-live="polite">
    {items.map(item => {
      const Icon = TOAST_ICONS[item.tone]
      return <div key={item.id} className={`app-toast app-toast--${item.tone}`} role={item.tone === 'danger' ? 'alert' : 'status'}>
        <Icon size={18} aria-hidden="true" />
        <span>{item.message}</span>
        <button type="button" className="app-toast__close" aria-label="Dismiss" onClick={() => dismissToast(item.id)}><X size={16} /></button>
      </div>
    })}
  </div>
}

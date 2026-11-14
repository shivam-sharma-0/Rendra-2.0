import type { CSSProperties, ReactNode } from 'react'

type Slots = { eyebrow?: ReactNode; title?: ReactNode; description?: ReactNode; actions?: ReactNode }

export function PageHeader({ eyebrow, title, description, actions }: Slots & { title: ReactNode }) {
  return <header className="app-page-header app-fade-in">
    <div className="app-stack">
      {eyebrow && <p className="app-eyebrow">{eyebrow}</p>}
      <h1>{title}</h1>
      {description && <p className="app-lead app-muted">{description}</p>}
    </div>
    {actions && <div className="app-actions">{actions}</div>}
  </header>
}

export function Hero({ eyebrow, title, description, actions, visual }: Slots & { title: ReactNode; visual?: ReactNode }) {
  return <section className="app-hero app-fade-in">
    <div className="app-hero__copy">
      {eyebrow && <p className="app-eyebrow">{eyebrow}</p>}
      <h1 className="app-hero__title">{title}</h1>
      {description && <p className="app-lead app-muted">{description}</p>}
      {actions && <div className="app-actions">{actions}</div>}
    </div>
    {visual && <div className="app-hero__visual">{visual}</div>}
  </section>
}

export function Section({ title, description, actions, children }: Slots & { children?: ReactNode }) {
  return <section className="app-section-block">
    {(title || description || actions) && <div className="app-section-head">
      <div className="app-stack">
        {title && <h2>{title}</h2>}
        {description && <p className="app-muted">{description}</p>}
      </div>
      {actions && <div className="app-actions">{actions}</div>}
    </div>}
    {children}
  </section>
}

export function Grid({ min = 16, children }: { min?: number | string; children?: ReactNode }) {
  return <div className="app-grid" style={{ '--app-grid-min': typeof min === 'number' ? `${min}rem` : min } as CSSProperties}>{children}</div>
}

export function Card({ title, description, actions, footer, children, onClick }: Slots & {
  footer?: ReactNode; children?: ReactNode; onClick?: () => void
}) {
  const body = <>
    {(title || actions) && <div className="app-card__head">
      <div className="app-stack">
        {title && <h3>{title}</h3>}
        {description && <p className="app-muted">{description}</p>}
      </div>
      {actions && <div className="app-actions">{actions}</div>}
    </div>}
    {!title && description && <p className="app-muted">{description}</p>}
    {children}
    {footer && <div className="app-card__footer">{footer}</div>}
  </>
  return onClick
    ? <button type="button" className="app-card app-card--interactive app-stack" onClick={onClick}>{body}</button>
    : <article className="app-card app-stack">{body}</article>
}

export function Stat({ label, value, hint, trend = 'flat' }: {
  label: ReactNode; value: ReactNode; hint?: ReactNode; trend?: 'up' | 'down' | 'flat'
}) {
  return <div className="app-stat">
    <span className="app-stat__label">{label}</span>
    <strong className="app-stat__value">{value}</strong>
    {hint && <span className={`app-stat__hint app-stat__hint--${trend}`}>{hint}</span>}
  </div>
}

export function StatGrid({ children }: { children?: ReactNode }) {
  return <div className="app-stat-grid">{children}</div>
}

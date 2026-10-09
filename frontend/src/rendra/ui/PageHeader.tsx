import type { ReactNode } from 'react'
import './ui.css'

type Props = { title: ReactNode; description?: string; action?: ReactNode }

/** Title, one-line description and an optional primary action for a list screen. */
export default function PageHeader({ title, description, action }: Props) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </header>
  )
}

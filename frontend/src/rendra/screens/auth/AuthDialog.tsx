import { useEffect, useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { NeuIconButton } from '../../../components/neu'
import { Ico } from '../../parts'
import { ICON } from '../../icons'

type Props = {
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}

/**
 * Modal shell for the auth screens: frosted backdrop, raised panel, brand mark,
 * close button and heading. Escape closes it, like any dialog.
 */
export default function AuthDialog({ title, onClose, children, footer }: Props) {
  const titleId = useId()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="kau-backdrop">
      <section className="kau-dialog neu-panel" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="kau-top">
          <Link to="/" className="kau-brand" aria-label="Rendra home"><span className="k-mark"><i /></span></Link>
          <NeuIconButton aria-label="Close" onClick={onClose}><Ico d={ICON.close} /></NeuIconButton>
        </div>

        <header className="kau-head">
          <span className="kau-eyebrow">Start <em>building.</em></span>
          <h1 id={titleId}>{title}</h1>
        </header>

        {children}

        {footer && <footer className="kau-foot">{footer}</footer>}
      </section>
    </div>
  )
}

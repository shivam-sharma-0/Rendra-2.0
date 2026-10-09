import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ICON } from '../icons'
import ShellIcon from './ShellIcon'

const DISMISSED_KEY = 'rendra_hide_pro'

function readDismissed(): boolean {
  try { return localStorage.getItem(DISMISSED_KEY) === 'true' } catch { return false }
}

/** Dismissible upgrade prompt; the dismissal is remembered in this browser. */
export default function GoProCard() {
  const [dismissed, setDismissed] = useState(readDismissed)
  if (dismissed) return null

  const dismiss = () => {
    setDismissed(true)
    try { localStorage.setItem(DISMISSED_KEY, 'true') } catch { /* storage unavailable: hide for this session only */ }
  }

  return (
    <div className="kap-pro">
      <span className="kap-pro-orb" />
      <button type="button" className="kap-pro-close" onClick={dismiss} aria-label="Dismiss"><ShellIcon d={ICON.close} size={15} /></button>
      <div style={{ position: 'relative', paddingRight: '12px' }}><b>Go Pro</b><p>More builds, custom domains and private projects.</p></div>
      <Link to="/pricing">See plans</Link>
    </div>
  )
}

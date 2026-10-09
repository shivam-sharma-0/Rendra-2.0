import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { ICON } from '../icons'
import { initials } from '../parts'
import ShellIcon from './ShellIcon'
import type { PopoverControl } from './types'
import { RAIL_WIDTH } from './useSidebarState'

const LINKS = [
  { label: 'Profile', to: '/settings/profile', icon: ICON.profile },
  { label: 'Workspace settings', to: '/settings/members', icon: ICON.gear },
  { label: 'Billing & usage', to: '/settings/billing-and-usage', icon: ICON.billing },
  { label: 'Help & docs', to: '/dashboard', icon: ICON.help },
] as const

/** Signed-in user button at the bottom of the sidebar, with settings links and log out. */
export default function AccountMenu({ collapsed, open, onToggle, onClose }: PopoverControl & { collapsed: boolean }) {
  const nav = useNavigate()
  const { user, signOut } = useAuth()
  const label = user?.name || user?.email || 'Account'

  return (
    <>
      <button type="button" className="kap-me" aria-expanded={open} onClick={onToggle}>
        <span className="kap-me-badge">{initials(label)}</span>
        <span className="kap-me-name">{user?.name || 'Rendra builder'}</span>
      </button>
      {open && (
        <div className="kap-pop" style={{ left: collapsed ? RAIL_WIDTH : 14, bottom: collapsed ? 8 : 56, width: 244 }}>
          {user?.email && (
            <>
              <div className="kap-pop-user"><span className="kap-pop-user-mail" title={user.email}>{user.email}</span></div>
              <div className="kap-pop-sep" />
            </>
          )}
          {LINKS.map((m) => (
            <Link key={m.label} to={m.to} className="kap-pop-item" onClick={onClose}><ShellIcon d={m.icon} size={16} />{m.label}</Link>
          ))}
          <div className="kap-pop-sep" />
          <button type="button" className="kap-pop-item" style={{ color: '#B42318' }} onClick={() => { signOut(); nav('/') }}>
            <ShellIcon d={ICON.logout} size={16} />Log out
          </button>
        </div>
      )}
    </>
  )
}

import { useNavigate } from 'react-router-dom'
import { ICON } from '../icons'
import NotificationsMenu from './NotificationsMenu'
import ShellIcon from './ShellIcon'
import type { NavId, PopoverControl } from './types'

type Props = {
  active?: NavId
  notifications: PopoverControl
  /** Opens the sidebar drawer; the button only shows on compact screens. */
  onOpenMenu: () => void
}

/** Bar above the main surface: menu (compact screens), a "what's new" banner on home or a search launcher, and notifications. */
export default function TopBar({ active, notifications, onOpenMenu }: Props) {
  const nav = useNavigate()

  return (
    <div className="kap-top">
      <button type="button" className="kap-menu-btn" aria-label="Open menu" onClick={onOpenMenu}><ShellIcon d={ICON.menu} size={18} /></button>
      {active === 'home' ? (
        <span className="kap-whatsnew"><span>New</span>Multiplayer — build together in one project<ShellIcon d={ICON.arrowRight} size={13} /></span>
      ) : (
        <button type="button" className="kap-search-btn" onClick={() => nav('/search')}>
          <ShellIcon d={ICON.search} size={15} /><span>Search projects, chats and actions</span><kbd>⌘K</kbd>
        </button>
      )}
      <div style={{ flexGrow: 1 }} />
      <NotificationsMenu {...notifications} />
    </div>
  )
}

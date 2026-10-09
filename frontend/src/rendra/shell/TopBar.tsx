import { useNavigate } from 'react-router-dom'
import { ICON } from '../icons'
import NotificationsMenu from './NotificationsMenu'
import ShellIcon from './ShellIcon'
import type { NavId, PopoverControl } from './types'

/** Bar above the main surface: a "what's new" banner on home, a search launcher elsewhere, and notifications. */
export default function TopBar({ active, notifications }: { active?: NavId; notifications: PopoverControl }) {
  const nav = useNavigate()

  return (
    <div className="kap-top">
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

import { AppIcon } from '../../components/icons'
import { useOpenSearch } from '../screens/search/searchRoute'
import NotificationsMenu from './NotificationsMenu'
import type { NavId, PopoverControl } from './types'

type Props = {
  active?: NavId
  notifications: PopoverControl
  /** Opens the sidebar drawer; the button only shows on compact screens. */
  onOpenMenu: () => void
}

/** Bar above the main surface: menu (compact screens), a "what's new" banner on home or a search launcher, and notifications. */
export default function TopBar({ active, notifications, onOpenMenu }: Props) {
  const openSearch = useOpenSearch()

  return (
    <div className="kap-top">
      <button type="button" className="kap-menu-btn" aria-label="Open menu" onClick={onOpenMenu}><AppIcon name="menu" size={20} /></button>
      {active === 'home' ? (
        <span className="kap-whatsnew"><span>New</span>Multiplayer — build together in one project<AppIcon name="arrowRight" /></span>
      ) : (
        <button type="button" className="kap-search-btn" onClick={openSearch}>
          <AppIcon name="search" /><span>Search projects, chats and actions</span><kbd>⌘K</kbd>
        </button>
      )}
      <div style={{ flexGrow: 1 }} />
      <NotificationsMenu {...notifications} />
    </div>
  )
}

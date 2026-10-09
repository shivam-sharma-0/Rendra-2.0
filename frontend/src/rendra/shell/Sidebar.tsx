import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { cx } from '../../lib/cx'
import { ICON } from '../icons'
import AccountMenu from './AccountMenu'
import GoProCard from './GoProCard'
import RecentsList from './RecentsList'
import ShellIcon from './ShellIcon'
import SidebarNav from './SidebarNav'
import type { NavId, PopoverControl } from './types'
import { useEscape } from './useEscape'
import WorkspaceSwitcher from './WorkspaceSwitcher'

type Props = {
  active?: NavId
  openProjectId: string | null
  chatsOpen: boolean
  onOpenChats: () => void
  workspaceMenu: PopoverControl
  accountMenu: PopoverControl
  /** Docked as an icon rail (wide screens only). */
  collapsed: boolean
  onToggleCollapsed: () => void
  /** Compact screens: the sidebar is an overlay drawer, shown while `drawerOpen`. */
  compact: boolean
  drawerOpen: boolean
  onCloseDrawer: () => void
}

/** Left sidebar: brand, workspace, navigation, recents, upsell and account. */
export default function Sidebar(props: Props) {
  const { active, openProjectId, chatsOpen, onOpenChats, workspaceMenu, accountMenu, collapsed, onToggleCollapsed, compact, drawerOpen, onCloseDrawer } = props
  const { user } = useAuth()
  const userLabel = user?.name || user?.email || 'Account'
  useEscape(onCloseDrawer, drawerOpen)

  return (
    <>
      {drawerOpen && <div className="kap-drawer-scrim" onClick={onCloseDrawer} />}
      <aside
        className={cx('kap-side', collapsed && 'collapsed', compact && 'kap-side--drawer', drawerOpen && 'open')}
        aria-label="Sidebar"
        inert={compact && !drawerOpen}
      >
        <div className="kap-side-head">
          <Link to="/" className="kap-brand"><span className="kap-brand-orb"><i /></span><b>Rendra</b></Link>
          {compact ? (
            <button type="button" className="kap-side-toggle" aria-label="Close menu" onClick={onCloseDrawer}><ShellIcon d={ICON.close} size={17} /></button>
          ) : (
            <button type="button" className="kap-side-toggle" onClick={onToggleCollapsed} aria-expanded={!collapsed}
              aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} title={`${collapsed ? 'Expand' : 'Collapse'} sidebar (Ctrl+B)`}>
              <ShellIcon d={ICON.sidebar} size={17} />
            </button>
          )}
        </div>
        <WorkspaceSwitcher badgeLabel={userLabel} {...workspaceMenu} />
        <SidebarNav active={active} inProject={openProjectId !== null} chatsOpen={chatsOpen} onOpenChats={onOpenChats} />
        {!collapsed && <RecentsList openProjectId={openProjectId} />}
        <GoProCard />
        <AccountMenu collapsed={collapsed} {...accountMenu} />
      </aside>
    </>
  )
}

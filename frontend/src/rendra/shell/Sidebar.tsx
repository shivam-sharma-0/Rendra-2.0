import type { KeyboardEvent, MouseEvent } from 'react'
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
import { useEscape } from '../hooks/useEscape'
import { RAIL_WIDTH } from './useSidebarState'
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
  /** Docked width while expanded, and the edge's drag / keyboard / double-click handlers. */
  width: number
  resizing: boolean
  onResizeStart: (e: MouseEvent) => void
  onResizeKey: (e: KeyboardEvent) => void
  onResizeReset: () => void
  /** Compact screens: the sidebar is an overlay drawer, shown while `drawerOpen`. */
  compact: boolean
  drawerOpen: boolean
  onCloseDrawer: () => void
}

/** Left sidebar: brand, workspace, navigation, recents, upsell and account. */
export default function Sidebar(props: Props) {
  const { active, openProjectId, chatsOpen, onOpenChats, workspaceMenu, accountMenu, collapsed, onToggleCollapsed, compact, drawerOpen, onCloseDrawer } = props
  const docked = !compact
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
        style={docked && !collapsed ? { width: props.width } : undefined}
      >
        {docked && (
          <div
            className={cx('kap-resizer', props.resizing && 'active')}
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize sidebar"
            aria-valuenow={collapsed ? RAIL_WIDTH : props.width}
            tabIndex={0}
            title="Drag to resize · double-click to reset"
            onMouseDown={props.onResizeStart}
            onKeyDown={props.onResizeKey}
            onDoubleClick={props.onResizeReset}
          />
        )}
        <div className="kap-side-head">
          {collapsed ? (
            // On the rail the logo doubles as the expand control: hovering it shows the sidebar icon.
            <button type="button" className="kap-rail-toggle" onClick={onToggleCollapsed} aria-expanded={false}
              aria-label="Expand sidebar" title="Expand sidebar (Ctrl+B)">
              <span className="kap-brand-orb" aria-hidden><i /></span>
              <span className="kap-rail-toggle__icon" aria-hidden><ShellIcon d={ICON.sidebar} size={17} /></span>
            </button>
          ) : (
            <Link to="/" className="kap-brand"><span className="kap-brand-orb"><i /></span><b>Rendra</b></Link>
          )}
          {compact && (
            <button type="button" className="kap-side-toggle" aria-label="Close menu" onClick={onCloseDrawer}><ShellIcon d={ICON.close} size={17} /></button>
          )}
          {docked && !collapsed && (
            <button type="button" className="kap-side-toggle" onClick={onToggleCollapsed} aria-expanded
              aria-label="Collapse sidebar" title="Collapse sidebar (Ctrl+B)">
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

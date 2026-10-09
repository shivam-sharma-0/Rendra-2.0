import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import AccountMenu from './AccountMenu'
import GoProCard from './GoProCard'
import RecentsList from './RecentsList'
import SidebarNav from './SidebarNav'
import type { NavId, PopoverControl } from './types'
import { useSidebarWidth } from './useSidebarWidth'
import WorkspaceSwitcher from './WorkspaceSwitcher'

type Props = {
  active?: NavId
  openProjectId: string | null
  chatsOpen: boolean
  onOpenChats: () => void
  workspaceMenu: PopoverControl
  accountMenu: PopoverControl
}

/** Resizable left sidebar: brand, workspace, navigation, recents, upsell and account. */
export default function Sidebar({ active, openProjectId, chatsOpen, onOpenChats, workspaceMenu, accountMenu }: Props) {
  const { user } = useAuth()
  const { width, isResizing, collapsed, startResize } = useSidebarWidth()
  const userLabel = user?.name || user?.email || 'Account'

  return (
    <aside className={`kap-side ${collapsed ? 'collapsed' : ''}`} style={{ width }}>
      <div className={`kap-resizer ${isResizing ? 'active' : ''}`} onMouseDown={startResize} />

      <Link to="/" className="kap-brand"><span className="kap-brand-orb"><i /></span><b>Rendra</b></Link>
      <WorkspaceSwitcher badgeLabel={userLabel} {...workspaceMenu} />
      <SidebarNav active={active} inProject={openProjectId !== null} chatsOpen={chatsOpen} onOpenChats={onOpenChats} />
      {!collapsed && <RecentsList openProjectId={openProjectId} />}
      <GoProCard />
      <AccountMenu collapsed={collapsed} {...accountMenu} />
    </aside>
  )
}

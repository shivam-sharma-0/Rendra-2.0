import { useNavigate } from 'react-router-dom'
import { ICON } from '../icons'
import ShellIcon from './ShellIcon'
import type { NavId } from './types'

type NavItem = { id: NavId | 'search'; label: string; icon: string; to: string; kbd?: string }

const NAV: readonly NavItem[] = [
  { id: 'home', label: 'New', icon: ICON.pencil, to: '/dashboard' },
  { id: 'search', label: 'Search', icon: ICON.search, to: '/search', kbd: '⌘K' },
  { id: 'connectors', label: 'Connectors', icon: ICON.connectors, to: '/connectors' },
  { id: 'chats', label: 'Chats', icon: ICON.chats, to: '/chats' },
  { id: 'projects', label: 'Projects', icon: ICON.projects, to: '/projects' },
]

type Props = {
  active?: NavId
  /** No item is highlighted while a project is open. */
  inProject: boolean
  chatsOpen: boolean
  /** "Chats" opens the chats dialog instead of navigating. */
  onOpenChats: () => void
}

export default function SidebarNav({ active, inProject, chatsOpen, onOpenChats }: Props) {
  const nav = useNavigate()

  return (
    <nav className="kap-nav">
      {NAV.map((n) => {
        const on = !inProject && (active === n.id || (n.id === 'chats' && chatsOpen))
        return (
          <button key={n.id} type="button" className={on ? 'on' : ''} onClick={() => (n.id === 'chats' ? onOpenChats() : nav(n.to))}>
            <ShellIcon d={n.icon} size={18} /><span style={{ flexGrow: 1 }}>{n.label}</span>{n.kbd && <span className="kbd">{n.kbd}</span>}
          </button>
        )
      })}
    </nav>
  )
}
